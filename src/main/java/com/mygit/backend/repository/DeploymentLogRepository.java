package com.mygit.backend.repository;

import com.mygit.backend.model.DeploymentLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeploymentLogRepository extends JpaRepository<DeploymentLog, Long> {
    List<DeploymentLog> findByDeploymentIdOrderBySeqAsc(Long deploymentId);
}
