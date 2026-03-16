(function () {
  const MAX_PROPOSALS = 64;
  const PROPOSAL_STRIDE_FLOATS = 12;

  const WGSL = `
struct Globals {
  canvasWidth: f32,
  canvasHeight: f32,
  tileSize: f32,
  time: f32,
  viewX: f32,
  viewY: f32,
  viewWidth: f32,
  viewHeight: f32,
  worldWidth: f32,
  worldHeight: f32,
  proposalCount: f32,
  pad0: f32,
};

struct Proposal {
  meta0: vec4<f32>,
  meta1: vec4<f32>,
  meta2: vec4<f32>,
};

@group(0) @binding(0) var<uniform> globals: Globals;
@group(0) @binding(1) var<storage, read> proposals: array<Proposal, ${MAX_PROPOSALS}>;

struct VsOut {
  @builtin(position) position: vec4<f32>,
  @location(0) uv: vec2<f32>,
};

fn tile_color(tileType: f32) -> vec3<f32> {
  if (tileType < 0.5) {
    return vec3<f32>(0.93, 0.82, 0.58);
  }
  if (tileType < 1.5) {
    return vec3<f32>(0.16, 0.53, 0.40);
  }
  if (tileType < 2.5) {
    return vec3<f32>(0.29, 0.64, 0.87);
  }
  if (tileType < 3.5) {
    return vec3<f32>(0.42, 0.44, 0.48);
  }
  return vec3<f32>(0.78, 0.61, 0.42);
}

@vertex
fn vs_main(@builtin(vertex_index) vertexIndex: u32) -> VsOut {
  var positions = array<vec2<f32>, 3>(
    vec2<f32>(-1.0, -3.0),
    vec2<f32>(-1.0, 1.0),
    vec2<f32>(3.0, 1.0)
  );
  let pos = positions[vertexIndex];
  var out: VsOut;
  out.position = vec4<f32>(pos, 0.0, 1.0);
  out.uv = pos * 0.5 + vec2<f32>(0.5, 0.5);
  return out;
}

@fragment
fn fs_main(in: VsOut) -> @location(0) vec4<f32> {
  if (globals.tileSize <= 0.0) {
    return vec4<f32>(0.0, 0.0, 0.0, 0.0);
  }
  let pixel = vec2<f32>(in.uv.x * globals.canvasWidth, in.uv.y * globals.canvasHeight);
  let tile = vec2<f32>(globals.viewX, globals.viewY) + pixel / globals.tileSize;
  var color = vec3<f32>(0.0, 0.0, 0.0);
  var alpha = 0.0;

  for (var i: u32 = 0u; i < ${MAX_PROPOSALS}u; i = i + 1u) {
    if (f32(i) >= globals.proposalCount) {
      break;
    }
    let proposal = proposals[i];
    let anchor = proposal.meta0.xy;
    let radius = max(2.0, proposal.meta0.z);
    let support = proposal.meta0.w;
    let stage = proposal.meta1.x;
    let tileType = proposal.meta1.y;
    let createdAt = proposal.meta1.z;
    let expiresAt = proposal.meta1.w;
    let resolvedAt = proposal.meta2.x;
    let status = proposal.meta2.y;
    let waveStrength = proposal.meta2.z;
    let dist = distance(tile, anchor);
    let baseColor = tile_color(tileType);
    let age = max(0.0, globals.time - createdAt);
    let pressure = exp(-dist / max(1.0, radius * 0.62));
    let contest = clamp(1.0 - abs(support - 0.5) * 2.0, 0.0, 1.0);
    let directional = smoothstep(radius + 0.8, radius - 1.4, dist);
    let pulse = 0.5 + 0.5 * sin((dist * 1.7) - age * 6.5);
    let waveFront = smoothstep(1.15, 0.0, abs(dist - min(age * 2.4, radius * 1.3)));
    let pendingMask = pressure * (0.22 + stage * 0.12 + pulse * 0.18 + contest * 0.15) + waveFront * waveStrength * 0.26;
    let resolveAge = max(0.0, globals.time - resolvedAt);
    let resolveMask = smoothstep(1.4, 0.0, resolveAge) * smoothstep(radius * 1.4 + 1.0, 0.0, dist);
    let rejectedColor = vec3<f32>(0.94, 0.28, 0.21);
    var overlayColor = baseColor;
    if (status > 1.5) {
      overlayColor = rejectedColor;
    }
    var mask = pendingMask * directional;
    if (status > 0.5) {
      mask = resolveMask;
    }
    color += overlayColor * mask;
    alpha = max(alpha, mask);

    let fringe = smoothstep(radius + 1.2, radius - 0.4, dist) * (0.08 + contest * 0.08 + stage * 0.04);
    let fringeColor = baseColor * 0.65 + vec3<f32>(1.0, 0.98, 0.92) * 0.35;
    color += fringeColor * fringe;
    alpha = max(alpha, fringe);

    if (expiresAt > createdAt && status < 0.5) {
      let timeLeft = clamp((expiresAt - globals.time) / max(0.2, expiresAt - createdAt), 0.0, 1.0);
      let rim = smoothstep(radius * 0.92 + 0.9, radius * 0.92 - 0.2, dist) * (1.0 - timeLeft) * 0.18;
      color += vec3<f32>(1.0, 0.95, 0.82) * rim;
      alpha = max(alpha, rim);
    }
  }

  return vec4<f32>(min(color, vec3<f32>(1.0, 1.0, 1.0)), clamp(alpha, 0.0, 0.82));
}
`;

  class WorldFxRenderer {
    constructor(canvas) {
      this.canvas = canvas;
      this.ready = false;
      this.device = null;
      this.context = null;
      this.pipeline = null;
      this.bindGroup = null;
      this.uniformBuffer = null;
      this.proposalBuffer = null;
      this.format = null;
      this.lastSizeKey = '';
      this.proposalArray = new Float32Array(MAX_PROPOSALS * PROPOSAL_STRIDE_FLOATS);
      this.uniformArray = new Float32Array(12);
    }

    static async create(canvas) {
      const renderer = new WorldFxRenderer(canvas);
      await renderer.init();
      return renderer;
    }

    async init() {
      if (!this.canvas || !navigator.gpu) {
        return;
      }
      const adapter = await navigator.gpu.requestAdapter();
      if (!adapter) {
        return;
      }
      this.device = await adapter.requestDevice();
      this.context = this.canvas.getContext('webgpu');
      this.format = navigator.gpu.getPreferredCanvasFormat();
      this.context.configure({
        device: this.device,
        format: this.format,
        alphaMode: 'premultiplied'
      });
      const shaderModule = this.device.createShaderModule({ code: WGSL });
      this.uniformBuffer = this.device.createBuffer({
        size: 48,
        usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST
      });
      this.proposalBuffer = this.device.createBuffer({
        size: this.proposalArray.byteLength,
        usage: GPUBufferUsage.STORAGE | GPUBufferUsage.COPY_DST
      });
      this.pipeline = this.device.createRenderPipeline({
        layout: 'auto',
        vertex: {
          module: shaderModule,
          entryPoint: 'vs_main'
        },
        fragment: {
          module: shaderModule,
          entryPoint: 'fs_main',
          targets: [{
            format: this.format,
            blend: {
              color: {
                srcFactor: 'src-alpha',
                dstFactor: 'one-minus-src-alpha',
                operation: 'add'
              },
              alpha: {
                srcFactor: 'one',
                dstFactor: 'one-minus-src-alpha',
                operation: 'add'
              }
            }
          }]
        },
        primitive: {
          topology: 'triangle-list'
        }
      });
      this.bindGroup = this.device.createBindGroup({
        layout: this.pipeline.getBindGroupLayout(0),
        entries: [
          { binding: 0, resource: { buffer: this.uniformBuffer } },
          { binding: 1, resource: { buffer: this.proposalBuffer } }
        ]
      });
      this.ready = true;
    }

    resize(width, height) {
      if (!this.ready) {
        return;
      }
      const sizeKey = `${width}x${height}`;
      if (sizeKey === this.lastSizeKey) {
        return;
      }
      this.lastSizeKey = sizeKey;
      this.context.configure({
        device: this.device,
        format: this.format,
        alphaMode: 'premultiplied'
      });
    }

    render(input) {
      if (!this.ready || !input) {
        return;
      }
      this.resize(this.canvas.width, this.canvas.height);
      const proposals = (input.proposals || []).slice(0, MAX_PROPOSALS);
      this.uniformArray[0] = this.canvas.width;
      this.uniformArray[1] = this.canvas.height;
      this.uniformArray[2] = input.tileSize || 1;
      this.uniformArray[3] = (input.nowMs || performance.now()) / 1000;
      this.uniformArray[4] = input.viewX || 0;
      this.uniformArray[5] = input.viewY || 0;
      this.uniformArray[6] = input.viewWidth || 0;
      this.uniformArray[7] = input.viewHeight || 0;
      this.uniformArray[8] = input.worldWidth || 0;
      this.uniformArray[9] = input.worldHeight || 0;
      this.uniformArray[10] = proposals.length;
      this.uniformArray[11] = 0;
      this.proposalArray.fill(0);
      for (let i = 0; i < proposals.length; i++) {
        const base = i * PROPOSAL_STRIDE_FLOATS;
        const proposal = proposals[i];
        this.proposalArray[base + 0] = proposal.anchorX || 0;
        this.proposalArray[base + 1] = proposal.anchorY || 0;
        this.proposalArray[base + 2] = proposal.radius || 6;
        this.proposalArray[base + 3] = proposal.support || 0.5;
        this.proposalArray[base + 4] = proposal.stage || 0;
        this.proposalArray[base + 5] = proposal.tileType || 0;
        this.proposalArray[base + 6] = (proposal.createdAtMs || input.nowMs || performance.now()) / 1000;
        this.proposalArray[base + 7] = (proposal.expiresAtMs || proposal.createdAtMs || input.nowMs || performance.now()) / 1000;
        this.proposalArray[base + 8] = (proposal.resolvedAtMs || 0) / 1000;
        this.proposalArray[base + 9] = proposal.statusCode || 0;
        this.proposalArray[base + 10] = proposal.waveStrength || 0.7;
        this.proposalArray[base + 11] = 0;
      }
      this.device.queue.writeBuffer(this.uniformBuffer, 0, this.uniformArray.buffer, this.uniformArray.byteOffset, this.uniformArray.byteLength);
      this.device.queue.writeBuffer(this.proposalBuffer, 0, this.proposalArray.buffer, this.proposalArray.byteOffset, this.proposalArray.byteLength);
      const encoder = this.device.createCommandEncoder();
      const pass = encoder.beginRenderPass({
        colorAttachments: [{
          view: this.context.getCurrentTexture().createView(),
          clearValue: { r: 0, g: 0, b: 0, a: 0 },
          loadOp: 'clear',
          storeOp: 'store'
        }]
      });
      pass.setPipeline(this.pipeline);
      pass.setBindGroup(0, this.bindGroup);
      pass.draw(3, 1, 0, 0);
      pass.end();
      this.device.queue.submit([encoder.finish()]);
    }
  }

  window.WorldFxRenderer = WorldFxRenderer;
})();
