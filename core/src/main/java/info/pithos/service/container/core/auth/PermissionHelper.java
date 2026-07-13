/*
 * Copyright 2026 Pithos
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package info.pithos.service.container.core.auth;

import info.pithos.runtime.core.context.ErrorCode;
import info.pithos.runtime.core.context.ServiceException;
import info.pithos.runtime.model.protocol.Context.RequestContext;

import java.util.Set;

/**
 * Reads the {@code _permissions} attribute populated by a {@link UserContextResolver}
 * after a successful authz lookup and enforces access control in service handlers.
 *
 * <p>When the attribute is absent (bypass mode), all checks pass — preserving local-dev
 * behaviour where {@code bypassAuth=true} skips token validation and authz calls.
 *
 * <p>Each app stores its permission strings in its own constants class and calls
 * {@link #requirePermission} or {@link #hasPermission} with those values.
 */
public final class PermissionHelper {

    public static final String ATTR_PERMISSIONS = "_permissions";

    public static boolean hasPermission(RequestContext rc, String permission) {
        String perms = rc.getAttributesOrDefault(ATTR_PERMISSIONS, "");
        if (perms.isEmpty()) return true;
        return Set.of(perms.split(",")).contains(permission);
    }

    public static void requirePermission(RequestContext rc, String permission) {
        if (!hasPermission(rc, permission)) {
            throw new ServiceException(ErrorCode.FORBIDDEN,
                "Permission required: " + permission);
        }
    }

    private PermissionHelper() {}
}
