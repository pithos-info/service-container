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

import com.google.protobuf.Message;

/**
 * Async handler for HTTP PUT. Full replacement — {@code Req} carries the
 * complete resource state deserialised from the request body.
 */
public interface PutHandler<Req extends Message, Resp extends Message>
        extends ServiceHandler<Req, Resp> {
}
