package com.mygit.backend.service;

import com.mygit.backend.model.Repository;
import com.mygit.backend.model.User;
import com.mygit.backend.repository.RepositoryRepository;
import com.mygit.backend.repository.UserRepository;
import com.mygit.backend.security.CustomUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RepositoryLookupService {

    @Autowired private RepositoryRepository repos;
    @Autowired private UserRepository users;

    public String cleanRepoName(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        if (trimmed.endsWith(".mygit")) return trimmed.substring(0, trimmed.length() - 6);
        if (trimmed.endsWith(".git")) return trimmed.substring(0, trimmed.length() - 4);
        return trimmed;
    }

    public Repository resolve(String repoName, String ownerName, CustomUser principal) {
        String cleanName = cleanRepoName(repoName);
        if (ownerName != null && !ownerName.isBlank()) {
            User owner = users.findByUsername(ownerName).orElse(null);
            if (owner != null) {
                Repository r = repos.findByOwnerIdAndName(owner.getId(), cleanName).orElse(null);
                if (r != null && (!r.isPrivate() || (principal != null && r.getOwner().getId().equals(principal.getId())))) {
                    return r;
                }
            }
            return null;
        }
        if (principal != null) {
            Repository owned = repos.findByOwnerIdAndName(principal.getId(), cleanName).orElse(null);
            if (owned != null) return owned;
        }
        return repos.findByName(cleanName).stream()
                .filter(r -> !r.isPrivate() || (principal != null && r.getOwner().getId().equals(principal.getId())))
                .findFirst()
                .orElse(null);
    }
}
