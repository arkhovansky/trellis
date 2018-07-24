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
package org.trellisldp.http.impl;

import static java.net.URI.create;
import static java.time.Instant.now;
import static java.util.Collections.singletonMap;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static javax.ws.rs.core.HttpHeaders.CONTENT_TYPE;
import static javax.ws.rs.core.MediaType.APPLICATION_OCTET_STREAM;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN_TYPE;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.Status.CREATED;
import static javax.ws.rs.core.Response.Status.NOT_ACCEPTABLE;
import static javax.ws.rs.core.Response.Status.NO_CONTENT;
import static javax.ws.rs.core.Response.serverError;
import static javax.ws.rs.core.Response.status;
import static org.apache.commons.rdf.api.RDFSyntax.TURTLE;
import static org.slf4j.LoggerFactory.getLogger;
import static org.trellisldp.api.RDFUtils.TRELLIS_DATA_PREFIX;
import static org.trellisldp.api.Resource.SpecialResources.DELETED_RESOURCE;
import static org.trellisldp.api.Resource.SpecialResources.MISSING_RESOURCE;
import static org.trellisldp.http.domain.HttpConstants.ACL;
import static org.trellisldp.http.impl.RdfUtils.buildEtagHash;
import static org.trellisldp.http.impl.RdfUtils.ldpResourceTypes;
import static org.trellisldp.http.impl.RdfUtils.skolemizeQuads;
import static org.trellisldp.vocabulary.Trellis.PreferAccessControl;
import static org.trellisldp.vocabulary.Trellis.PreferUserManaged;
import static org.trellisldp.vocabulary.Trellis.UnsupportedInteractionModel;

import java.io.File;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.ResponseBuilder;

import org.apache.commons.rdf.api.IRI;
import org.apache.commons.rdf.api.Quad;
import org.apache.commons.rdf.api.RDFSyntax;
import org.apache.commons.rdf.api.Triple;
import org.slf4j.Logger;
import org.trellisldp.api.Binary;
import org.trellisldp.api.Resource;
import org.trellisldp.api.ServiceBundler;
import org.trellisldp.http.domain.LdpRequest;
import org.trellisldp.vocabulary.LDP;

/**
 * The PUT response handler.
 *
 * @author acoburn
 */
public class PutHandler extends MutatingLdpHandler {

    private static final Logger LOGGER = getLogger(PutHandler.class);

    private final IRI internalId;
    private final RDFSyntax rdfSyntax;
    private final IRI heuristicType;
    private final IRI graphName;
    private final IRI otherGraph;

    /**
     * Create a builder for an LDP PUT response.
     *
     * @param req the LDP request
     * @param entity the entity
     * @param trellis the Trellis application bundle
     * @param baseUrl the base URL
     */
    public PutHandler(final LdpRequest req, final File entity, final ServiceBundler trellis, final String baseUrl) {
        super(req, trellis, baseUrl, entity);
        this.internalId = rdf.createIRI(TRELLIS_DATA_PREFIX + req.getPath());
        this.rdfSyntax = ofNullable(req.getContentType()).map(MediaType::valueOf).flatMap(ct ->
                getServices().getIOService().supportedWriteSyntaxes().stream().filter(s ->
                    ct.isCompatible(MediaType.valueOf(s.mediaType()))).findFirst()).orElse(null);

        this.heuristicType = nonNull(req.getContentType()) && isNull(rdfSyntax) ? LDP.NonRDFSource : LDP.RDFSource;
        this.graphName = ACL.equals(req.getExt()) ? PreferAccessControl : PreferUserManaged;
        this.otherGraph = ACL.equals(req.getExt()) ? PreferUserManaged : PreferAccessControl;
    }

    /**
     * Initialize the response handler.
     * @param resource the resource
     * @return the response builder
     */
    public ResponseBuilder initialize(final Resource resource) {
        setResource(DELETED_RESOURCE.equals(resource) || MISSING_RESOURCE.equals(resource) ? null : resource);

        // Check the cache
        if (nonNull(getResource())) {
            final EntityTag etag;
            final Instant modified;
            final Optional<Instant> binaryModification = getResource().getBinary().map(Binary::getModified);

            if (binaryModification.isPresent() &&
                    !ofNullable(getRequest().getContentType()).flatMap(RDFSyntax::byMediaType).isPresent()) {
                modified = binaryModification.get();
                etag = new EntityTag(buildEtagHash(getIdentifier() + "BINARY",
                            modified, null));
            } else {
                modified = getResource().getModified();
                etag = new EntityTag(buildEtagHash(getIdentifier(), modified, getRequest().getPrefer()), true);
            }
            // Check the cache
            final ResponseBuilder cache = checkCache(modified, etag);
            if (nonNull(cache)) {
                return cache;
            }
        }

        // One cannot put binaries into the ACL graph
        if (ACL.equals(getRequest().getExt()) && isNull(rdfSyntax)) {
            return status(NOT_ACCEPTABLE);
        }

        mayContinue(true);
        return status(NO_CONTENT);
    }

    /**
     * Store the resource to the persistence layer.
     * @param builder the response builder
     * @return the response builder
     */
    public CompletableFuture<ResponseBuilder> setResource(final ResponseBuilder builder) {
        if (!mayContinue()) {
            return completedFuture(builder);
        }

        LOGGER.debug("Setting resource as {}", getIdentifier());

        final IRI ldpType = isBinaryDescription() ? LDP.NonRDFSource : ofNullable(getRequest().getLink())
            .filter(l -> "type".equals(l.getRel())).map(Link::getUri).map(URI::toString)
            .filter(l -> l.startsWith(LDP.getNamespace())).map(rdf::createIRI).filter(l -> !LDP.Resource.equals(l))
            .orElseGet(() -> ofNullable(getResource()).map(Resource::getInteractionModel).orElse(heuristicType));

        // Verify that the persistence layer supports the given interaction model
        if (!supportsInteractionModel(ldpType)) {
            mayContinue(false);
            return completedFuture(status(BAD_REQUEST)
                .link(UnsupportedInteractionModel.getIRIString(), LDP.constrainedBy.getIRIString())
                .entity("Unsupported interaction model provided").type(TEXT_PLAIN_TYPE));
        }

        // It is not possible to change the LDP type to a type that is not a subclass
        if (nonNull(getResource()) && !isBinaryDescription()
                && ldpResourceTypes(ldpType).noneMatch(getResource().getInteractionModel()::equals)) {
            LOGGER.error("Cannot change the LDP type to {} for {}", ldpType, getIdentifier());
            mayContinue(false);
            return completedFuture(status(CONFLICT));
        }

        LOGGER.debug("Using LDP Type: {}", ldpType);

        final TrellisDataset mutable = TrellisDataset.createDataset();
        final TrellisDataset immutable = TrellisDataset.createDataset();

        return handleResourceUpdate(mutable, immutable, builder, ldpType)
            .whenComplete((a, b) -> mutable.close())
            .whenComplete((a, b) -> immutable.close());
    }

    @Override
    protected IRI getInternalId() {
        return internalId;
    }

    @Override
    protected String getIdentifier() {
        return super.getIdentifier() + (ACL.equals(getRequest().getExt()) ? "?ext=acl" : "");
    }

    private CompletableFuture<ResponseBuilder> handleResourceUpdate(final TrellisDataset mutable,
            final TrellisDataset immutable, final ResponseBuilder builder, final IRI ldpType) {
        final Binary binary;

        // Add user-supplied data
        if (LDP.NonRDFSource.equals(ldpType) && isNull(rdfSyntax)) {
            // Check the expected digest value
            final ResponseBuilder digest = checkForBadDigest(getRequest().getDigest());
            if (nonNull(digest)) {
                mayContinue(false);
                return completedFuture(digest);
            }

            final String mimeType = ofNullable(getRequest().getContentType()).orElse(APPLICATION_OCTET_STREAM);
            final IRI binaryLocation = rdf.createIRI(getServices().getBinaryService().generateIdentifier());

            // Persist the content
            final ResponseBuilder persist = persistContent(binaryLocation, singletonMap(CONTENT_TYPE, mimeType));
            if (nonNull(persist)) {
                mayContinue(false);
                return completedFuture(persist);
            }

            binary = new Binary(binaryLocation, now(), mimeType, getEntityLength());
        } else {
            final ResponseBuilder readError = readEntityIntoDataset(graphName, ofNullable(rdfSyntax).orElse(TURTLE),
                    mutable);
            if (nonNull(readError)) {
                mayContinue(false);
                return completedFuture(readError);
            }

            // Check for any constraints
            final ResponseBuilder constraintError = ACL.equals(getRequest().getExt())
                ? checkConstraint(mutable.getGraph(PreferAccessControl).orElse(null), LDP.RDFSource,
                        ofNullable(rdfSyntax).orElse(TURTLE))
                : checkConstraint(mutable.getGraph(PreferUserManaged).orElse(null), ldpType,
                        ofNullable(rdfSyntax).orElse(TURTLE));

            if (nonNull(constraintError)) {
                mayContinue(false);
                return completedFuture(constraintError);
            }
            binary = ofNullable(getResource()).flatMap(Resource::getBinary).orElse(null);
        }

        if (nonNull(getResource())) {
            try (final Stream<? extends Triple> remaining = getResource().stream(otherGraph)) {
                remaining.map(t -> rdf.createQuad(otherGraph, t.getSubject(), t.getPredicate(), t.getObject()))
                    .forEachOrdered(mutable::add);
            }
        }

        auditQuads().stream().map(skolemizeQuads(getServices().getResourceService(), getBaseUrl()))
            .forEachOrdered(immutable::add);

        ldpResourceTypes(effectiveLdpType(ldpType)).map(IRI::getIRIString).forEach(type -> builder.link(type, "type"));

        return createOrReplace(ldpType, mutable, binary)
            .thenCombine(getServices().getResourceService().add(internalId, getSession(), immutable.asDataset()),
                    this::handleWriteResults)
            .thenApply(handleResponse(builder));
    }

    private IRI effectiveLdpType(final IRI ldpType) {
        return LDP.NonRDFSource.equals(ldpType) && isBinaryDescription() ? LDP.RDFSource : ldpType;
    }

    private Function<Boolean, ResponseBuilder> handleResponse(final ResponseBuilder builder) {
        return success -> {
            if (success) {
                if (isNull(getResource())) {
                    builder.status(CREATED).contentLocation(create(getIdentifier()));
                }
                return builder;
            }
            mayContinue(false);
            return serverError().type(TEXT_PLAIN_TYPE)
                    .entity("Unable to persist data. Please consult the logs for more information");
        };
    }

    private CompletableFuture<Boolean> createOrReplace(final IRI ldpType, final TrellisDataset ds, final Binary b) {
        final IRI c = getServices().getResourceService().getContainer(internalId).orElse(null);
        return nonNull(getResource())
            ? getServices().getResourceService().replace(internalId, getSession(), ldpType, ds.asDataset(), c, b)
            : getServices().getResourceService().create(internalId, getSession(), ldpType, ds.asDataset(), c, b);
    }

    private List<Quad> auditQuads() {
        if (nonNull(getResource())) {
            return getServices().getAuditService().update(internalId, getSession());
        }
        return getServices().getAuditService().creation(internalId, getSession());
    }

    private Boolean isBinaryDescription() {
        return nonNull(getResource()) && LDP.NonRDFSource.equals(getResource().getInteractionModel())
            && nonNull(rdfSyntax);
    }
}
