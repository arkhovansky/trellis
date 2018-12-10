/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.trellisldp.api;

import static java.util.Collections.emptySortedSet;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.trellisldp.api.Resource.SpecialResources.MISSING_RESOURCE;

import java.time.Instant;
import java.util.SortedSet;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.rdf.api.IRI;

/**
 * A no-op MementoService implementation.
 */
public class NoopMementoService implements MementoService {

    @Override
    public CompletableFuture<Void> put(final ResourceService resourceService, final IRI identifier) {
        return completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> put(final Resource resource) {
        return completedFuture(null);
    }

    @Override
    public CompletableFuture<Resource> get(final IRI identifier, final Instant time) {
        return completedFuture(MISSING_RESOURCE);
    }

    @Override
    public CompletableFuture<SortedSet<Instant>> mementos(final IRI identifier) {
        return completedFuture(emptySortedSet());
    }
}
