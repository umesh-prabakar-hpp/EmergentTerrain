#!/usr/bin/env bash
# =====================================================================
# provision-build-box.sh — §32.5e: fire the AWS corpus-build box
# =====================================================================
# Run AFTER `aws login` succeeds. Provisions the transient EC2 box from the
# §32.5e runbook (m6i.2xlarge + 500 GB gp3, us-east-1), reusing the existing
# gpu-cli-key pair, then prints the copy-up + build commands. Idempotent-ish:
# reuses a security group by name; tags the instance so teardown is easy.
#
# Env knobs (all optional):
#   KEY_NAME     EC2 key pair name           (default gpu-cli-key)
#   ITYPE        instance type               (default m6i.2xlarge)
#   VOL_GB       gp3 volume size             (default 500)
#   REGION       AWS region                  (default from ~/.aws/config = us-east-1)
# =====================================================================
set -euo pipefail

KEY_NAME="${KEY_NAME:-gpu-cli-key}"
ITYPE="${ITYPE:-m6i.2xlarge}"
VOL_GB="${VOL_GB:-500}"
REGION="${REGION:-$(aws configure get region 2>/dev/null || echo us-east-1)}"
SG_NAME="et-corpus-build-sg"
TAG="et-corpus-build"
PEM="$HOME/.ssh/${KEY_NAME}.pem"

echo "== preflight =="
aws sts get-caller-identity >/dev/null 2>&1 || { echo "NOT authenticated — run: aws login"; exit 1; }
aws ec2 describe-key-pairs --key-names "$KEY_NAME" --region "$REGION" >/dev/null 2>&1 \
  || { echo "key pair '$KEY_NAME' not found in $REGION — set KEY_NAME=..."; exit 1; }
[ -f "$PEM" ] || echo "WARN: $PEM not found locally — you'll need it to ssh in."

echo "== AMI (Amazon Linux 2023) via SSM =="
AMI=$(aws ssm get-parameter --region "$REGION" \
  --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
  --query 'Parameter.Value' --output text)
echo "  $AMI"

echo "== security group (SSH from this machine's IP) =="
MYIP=$(curl -s https://checkip.amazonaws.com | tr -d '\n')
SG=$(aws ec2 describe-security-groups --region "$REGION" --group-names "$SG_NAME" \
      --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null || echo "")
if [ -z "$SG" ] || [ "$SG" = "None" ]; then
  SG=$(aws ec2 create-security-group --region "$REGION" --group-name "$SG_NAME" \
        --description "ET corpus build (transient)" --query 'GroupId' --output text)
fi
aws ec2 authorize-security-group-ingress --region "$REGION" --group-id "$SG" \
  --protocol tcp --port 22 --cidr "${MYIP}/32" >/dev/null 2>&1 || true
echo "  $SG (ssh from ${MYIP}/32)"

echo "== launching $ITYPE + ${VOL_GB}GB gp3 =="
IID=$(aws ec2 run-instances --region "$REGION" --image-id "$AMI" --instance-type "$ITYPE" \
  --key-name "$KEY_NAME" --security-group-ids "$SG" \
  --block-device-mappings "[{\"DeviceName\":\"/dev/xvda\",\"Ebs\":{\"VolumeSize\":${VOL_GB},\"VolumeType\":\"gp3\",\"DeleteOnTermination\":true}}]" \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=${TAG}}]" \
  --query 'Instances[0].InstanceId' --output text)
echo "  instance $IID — waiting for running..."
aws ec2 wait instance-running --region "$REGION" --instance-ids "$IID"
IP=$(aws ec2 describe-instances --region "$REGION" --instance-ids "$IID" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
echo "  UP: $IP"

cat <<EOF

======================================================================
Build box ready: $IID @ $IP   (region $REGION, type $ITYPE, ${VOL_GB}GB)
----------------------------------------------------------------------
# 1. copy join-keys + scripts up
scp -i "$PEM" \\
  ~/.emergentterrain/corpus-nt/corpus-join-keys.jsonl \\
  scripts/aws/build-fulltext-jedibear.py scripts/aws/build-fulltext-ar5iv-bulk.py \\
  scripts/aws/merge-fulltext.py \\
  ec2-user@$IP:~/

# 2. on the box: deps + broad layer (jedibear, no key)
ssh -i "$PEM" ec2-user@$IP
  sudo dnf -y install python3-pip && pip install huggingface_hub pyarrow
  python3 build-fulltext-jedibear.py --keys corpus-join-keys.jsonl

# 3. math layer: download ar5iv zip from your License-Agreement link, then
  curl -o ar5iv.zip "<ar5iv download URL>"
  python3 build-fulltext-ar5iv-bulk.py --keys corpus-join-keys.jsonl --zips ar5iv.zip

# 4. merge + ship home
  python3 merge-fulltext.py --ar5iv ar5iv-fulltext.jsonl --s2orc jedibear-fulltext.jsonl --outdir fulltext
  tar czf fulltext.tgz fulltext fulltext-manifest.jsonl
  # (from local): scp -i "$PEM" ec2-user@$IP:~/fulltext.tgz ~/.emergentterrain/corpus-nt/

# 5. TEARDOWN when done (stops billing):
aws ec2 terminate-instances --region "$REGION" --instance-ids $IID
======================================================================
EOF
