package com.example.cookiedemo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Our database access layer.
 *
 * We only write the interface — Spring writes the actual SQL for us at startup.
 *
 * From JpaRepository we get for free:  save(user), findAll(), deleteAll(), count() ...
 * And by simply NAMING a method "findByUsername", Spring generates:
 *     SELECT * FROM app_user WHERE username = ?
 */
public interface UserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    /**
     * "Which account does this browser belong to?"
     *
     * The lookup that answers the awkward case: a device cookie arrives, and we
     * have to know whether it is the person typing their password or somebody
     * else's machine. Optional because the hash may match nobody — an admin
     * reset the binding, or the database was rebuilt, and the browser is still
     * carrying a cookie that no longer means anything.
     */
    Optional<AppUser> findByDeviceTokenHash(String deviceTokenHash);

    /** Used by the admin screen to list the people it can act on. */
    List<AppUser> findByRoleOrderByUsernameAsc(Role role);
}
