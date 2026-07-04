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

package info.pithos.service.container.core;

import info.pithos.runtime.core.context.ErrorCode;
import info.pithos.runtime.core.context.ServiceException;

// Implemented by per-handler operation enums (e.g. ProductOperation, RoleOperation).
// Provides standard metric name derivation for service-tier (Tier 2) recording.
// App-specific enums extend this: public enum RoleOperation implements ServiceOperation { ... }
public interface ServiceOperation {

    String stem();

    default String latency() { return stem() + ".latency"; }
    default String timeout() { return stem() + ".timeout"; }

    // outcome for a specific HTTP status code, e.g. "login.OK", "login.CREATED", "login.UNAUTHORIZED"
    default String outcome(ErrorCode code) { return stem() + "." + code.name(); }

    // Resolves the outcome metric name from an exception or a success code.
    // Timeout is detected by walking the cause chain before checking ErrorCode.
    static String resolve(ServiceOperation op, ErrorCode successCode, Throwable ex) {
        if (ex == null) return op.outcome(successCode);
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof java.util.concurrent.TimeoutException) return op.timeout();
            cause = cause.getCause();
        }
        ErrorCode code = ex instanceof ServiceException se ? se.getErrorCode() : ErrorCode.INTERNAL_SERVER_ERROR;
        return op.outcome(code);
    }
}
