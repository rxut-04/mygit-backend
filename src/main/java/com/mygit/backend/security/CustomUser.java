package com.mygit.backend.security;

import java.io.Serializable;

public class CustomUser implements Serializable {
    private final Long id;
    private final String username;

    public CustomUser(Long id, String username) {
        this.id = id;
        this.username = username;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    @Override
    public String toString() {
        return "CustomUser{" +
                "id=" + id +
                ", username='" + username + '\'' +
                '}';
    }
}
