package com.emergentterrain.repo;

import com.emergentterrain.research.FormalFinding;
import com.emergentterrain.research.RelationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FormalFindingRepository extends JpaRepository<FormalFinding, UUID> {

    List<FormalFinding> findByPaperId(String paperId);

    List<FormalFinding> findByDomain(String domain);

    List<FormalFinding> findByDependentVariable(String dependentVariable);

    List<FormalFinding> findByDomainAndDependentVariable(String domain, String dependentVariable);

    List<FormalFinding> findByDependentVariableAndRelationType(
            String dependentVariable, RelationType relationType);
}
