package com.mygit.backend.repository;

import com.mygit.backend.model.RepositoryFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

@org.springframework.stereotype.Repository
public interface RepositoryFileRepository extends JpaRepository<RepositoryFile, Long> {

    List<RepositoryFile> findByRepositoryIdAndBranchName(Long repositoryId, String branchName);

    List<RepositoryFile> findByRepositoryId(Long repositoryId);

    Optional<RepositoryFile> findByRepositoryIdAndBranchNameAndFilePath(Long repositoryId, String branchName, String filePath);

    void deleteByRepositoryIdAndBranchName(Long repositoryId, String branchName);
}
