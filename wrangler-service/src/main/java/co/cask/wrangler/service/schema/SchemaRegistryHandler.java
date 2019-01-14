/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.wrangler.service.schema;

import co.cask.cdap.api.annotation.UseDataSet;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.api.service.http.AbstractHttpServiceHandler;
import co.cask.cdap.api.service.http.HttpServiceContext;
import co.cask.cdap.api.service.http.HttpServiceRequest;
import co.cask.cdap.api.service.http.HttpServiceResponder;
import co.cask.wrangler.dataset.schema.SchemaDescriptor;
import co.cask.wrangler.dataset.schema.SchemaNotFoundException;
import co.cask.wrangler.dataset.schema.SchemaRegistry;
import co.cask.wrangler.dataset.schema.SchemaRegistryException;
import co.cask.wrangler.proto.ServiceResponse;
import co.cask.wrangler.proto.schema.SchemaDescriptorType;
import co.cask.wrangler.proto.schema.SchemaEntry;
import co.cask.wrangler.proto.schema.SchemaEntryVersion;
import co.cask.wrangler.proto.schema.SchemaId;
import com.google.gson.Gson;

import java.nio.ByteBuffer;
import java.util.Set;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;

import static co.cask.wrangler.ServiceUtils.error;
import static co.cask.wrangler.ServiceUtils.notFound;
import static co.cask.wrangler.ServiceUtils.success;

/**
 * This class {@link SchemaRegistryHandler} provides schema management service.
 */
public class SchemaRegistryHandler extends AbstractHttpServiceHandler {
  private static final Gson GSON = new Gson();

  @UseDataSet(SchemaRegistry.DATASET_NAME)
  private Table table;

  private SchemaRegistry registry;

  /**
   * An implementation of HttpServiceHandler#initialize(HttpServiceContext). Stores the context
   * so that it can be used later.
   *
   * @param context the HTTP service runtime context
   * @throws Exception
   */
  @Override
  public void initialize(HttpServiceContext context) throws Exception {
    super.initialize(context);
    registry = new SchemaRegistry(table);
  }

  @PUT
  @Path("contexts/{context}/schemas")
  public void create(HttpServiceRequest request, HttpServiceResponder responder,
                     @PathParam("context") String context,
                     @QueryParam("id") String id, @QueryParam("name") String name,
                     @QueryParam("description") String description, @QueryParam("type") String type) {
    create(responder, SchemaId.of(context, id), name, description, type);
  }

  @PUT
  @Path("schemas")
  public void create(HttpServiceRequest request, HttpServiceResponder responder,
                     @QueryParam("id") String id, @QueryParam("name") String name,
                     @QueryParam("description") String description, @QueryParam("type") String type) {
    create(responder, SchemaId.of(getContext().getNamespace(), id), name, description, type);
  }

  /**
   * Creates an entry for Schema with id, name, description and type of schema.
   * if the 'id' already exists, then it overwrites the data with new information.
   * This responds with HTTP - OK (200) or Internal Error (500).
   *
   * @param responder the HTTP response handler
   * @param id the id of the schema to create
   * @param name the name of the schema to create
   * @param description the description of the schema
   * @param type the type of schema
   */
  private void create(HttpServiceResponder responder, SchemaId id, String name,
                      String description, String type) {
    try {
      if (id.getId() == null || id.getId().isEmpty()) {
        error(responder, "Schema id must be specified.");
        return;
      }
      if (name == null || name.isEmpty()) {
        error(responder, "Schema name must be specified.");
        return;
      }
      SchemaDescriptorType descriptorType = GSON.fromJson(type, SchemaDescriptorType.class);
      if (descriptorType == null) {
        error(responder, String.format("Schema type '%s' is invalid.", type));
        return;
      }
      if (description == null || description.isEmpty()) {
        error(responder, "Schema description must be specified.");
        return;
      }
      SchemaDescriptor descriptor = new SchemaDescriptor(id, name, description, descriptorType);
      registry.write(descriptor);
      success(responder, String.format("Successfully created schema entry with id '%s', name '%s'",
                                       id, name));
    } catch (SchemaRegistryException e) {
      error(responder, e.getMessage());
    }
  }

  @POST
  @Path("schemas/{id}")
  public void upload(HttpServiceRequest request, HttpServiceResponder responder, @PathParam("id") String id) {
    uploadSchema(request, responder, SchemaId.of(getContext().getNamespace(), id));
  }

  @POST
  @Path("contexts/{context}/schemas/{id}")
  public void upload(HttpServiceRequest request, HttpServiceResponder responder,
                     @PathParam("context") String context, @PathParam("id") String id) {
    uploadSchema(request, responder, SchemaId.of(context, id));
  }

  /**
   * Uploads a schema to be associated with the schema id.
   * This API will automatically increment the schema version. Upon adding the schema to associated id in
   * schema registry, it returns the version to which the uploaded schema was added to.
   *
   * Following is the response when it's HTTP OK(200)
   * {
   *   "status" : "OK",
   *   "message" : "Success",
   *   "count" : 1,
   *   "values" : [
   *      {
   *        "id" : <id-passed-in-REST-call>
   *        "version" : <version-schema-written-to>
   *      }
   *   ]
   * }
   *
   * On any issues, returns error with proper error message and Internal Server error (500).
   *
   * @param request the HTTP request handler
   * @param responder the HTTP response handler
   * @param id the id of the schema to upload
   */
  private void uploadSchema(HttpServiceRequest request, HttpServiceResponder responder, SchemaId id) {
    byte[] bytes = null;
    ByteBuffer content = request.getContent();
    if (content != null && content.hasRemaining()) {
      bytes = new byte[content.remaining()];
      content.get(bytes);
    }

    if (bytes == null) {
      error(responder, "Schema does not exist.");
      return;
    }

    try {
      long version = registry.add(id, bytes);
      ServiceResponse<SchemaEntryVersion> response = new ServiceResponse<>(new SchemaEntryVersion(id, version));
      responder.sendJson(response);
    } catch (SchemaNotFoundException e) {
      notFound(responder, e.getMessage());
    } catch (SchemaRegistryException e) {
      error(responder, e.getMessage());
    }
  }

  @DELETE
  @Path("schemas/{id}")
  public void delete(HttpServiceRequest request, HttpServiceResponder responder,
                     @PathParam("id") String id) {
    deleteSchema(responder, SchemaId.of(getContext().getNamespace(), id));
  }

  @DELETE
  @Path("contexts/{context}/schemas/{id}")
  public void delete(HttpServiceRequest request, HttpServiceResponder responder,
                     @PathParam("context") String context, @PathParam("id") String id) {
    deleteSchema(responder, SchemaId.of(context, id));
  }

  /**
   * Deletes the entire schema from the registry.
   *
   * Everything related to the schema id is deleted completely.
   * All versions of schema are also deleted.
   *
   * @param responder the HTTP response handler
   * @param id the id of the schema to delete
   */
  private void deleteSchema(HttpServiceResponder responder, SchemaId id) {
    try {
      if (registry.hasSchema(id)) {
        notFound(responder, "Id " + id + " not found.");
        return;
      }
      registry.delete(id);
      success(responder, "Successfully deleted schema " + id);
    } catch (SchemaRegistryException e) {
      error(responder, e.getMessage());
    }
  }

  @DELETE
  @Path("schemas/{id}/versions/{version}")
  public void delete(HttpServiceRequest request, HttpServiceResponder responder,
                     @PathParam("id") String id, @PathParam("version") long version) {
    deleteEntry(responder, SchemaId.of(getContext().getNamespace(), id), version);
  }

  @DELETE
  @Path("contexts/{context}/schemas/{id}/versions/{version}")
  public void delete(HttpServiceRequest request, HttpServiceResponder responder,
                     @PathParam("context") String context, @PathParam("id") String id,
                     @PathParam("version") long version) {
    deleteEntry(responder, SchemaId.of(context, id), version);
  }

  /**
   * Deletes a schema entry from the registry.
   *
   * @param responder the HTTP response handler
   * @param id the schema id
   * @param version the schema entry version
   */
  private void deleteEntry(HttpServiceResponder responder, SchemaId id, long version) {
    try {
      registry.remove(id, version);
      success(responder, "Successfully deleted version '" + version + "' of schema " + id);
    } catch (SchemaNotFoundException e) {
      notFound(responder, e.getMessage());
    } catch (SchemaRegistryException e) {
      error(responder, e.getMessage());
    }
  }

  @GET
  @Path("schemas/{id}/versions/{version}")
  public void get(HttpServiceRequest request, HttpServiceResponder responder,
                  @PathParam("id") String id, @PathParam("version") long version) {
    getEntry(responder, SchemaId.of(getContext().getNamespace(), id), version);
  }

  @GET
  @Path("contexts/{context}/schemas/{id}/versions/{version}")
  public void get(HttpServiceRequest request, HttpServiceResponder responder,
                  @PathParam("context") String context, @PathParam("id") String id,
                  @PathParam("version") long version) {
    getEntry(responder, SchemaId.of(context, id), version);
  }

  /**
   * Returns information about the schema entry,
   * including schema requested along with versions available and other metadata.
   *
   * @param responder the HTTP response handler
   * @param id the schema id
   * @param version the schema entry version
   */
  private void getEntry(HttpServiceResponder responder, SchemaId id, long version) {
    try {
      SchemaEntry entry = registry.getEntry(id, version);
      ServiceResponse<SchemaEntry> response = new ServiceResponse<>(entry);
      responder.sendJson(response);
    } catch (SchemaNotFoundException e) {
      notFound(responder, e.getMessage());
    } catch (SchemaRegistryException e) {
      error(responder, e.getMessage());
    }
  }

  @GET
  @Path("schemas/{id}")
  public void get(HttpServiceRequest request, HttpServiceResponder responder,
                  @PathParam("id") String id) {
    get(responder, SchemaId.of(getContext().getNamespace(), id));
  }

  @GET
  @Path("contexts/{context}/schemas/{id}")
  public void get(HttpServiceRequest request, HttpServiceResponder responder,
                  @PathParam("context") String context, @PathParam("id") String id) {
    get(responder, SchemaId.of(context, id));
  }

  /**
   * Returns information about the schema, including schema requested along with versions available and other metadata.
   * This call will automatically detect the currect active version of schema.
   *
   * @param responder the HTTP response handler
   * @param id the schema id
   */
  private void get(HttpServiceResponder responder, SchemaId id) {
    try {
      SchemaEntry entry = registry.getEntry(id);
      ServiceResponse<SchemaEntry> response = new ServiceResponse<>(entry);
      responder.sendJson(response);
    } catch (SchemaNotFoundException e) {
      notFound(responder, e.getMessage());
    } catch (SchemaRegistryException e) {
      error(responder, e.getMessage());
    }
  }

  @GET
  @Path("schemas/{id}/versions")
  public void versions(HttpServiceRequest request, HttpServiceResponder responder, @PathParam("id") String id) {
    listVersions(responder, SchemaId.of(getContext().getNamespace(), id));
  }

  @GET
  @Path("contexts/{context}/schemas/{id}/versions")
  public void versions(HttpServiceRequest request, HttpServiceResponder responder,
                       @PathParam("context") String context, @PathParam("id") String id) {
    listVersions(responder, SchemaId.of(context, id));
  }

  /**
   * Returns list of versions for a given schema id.
   *
   * @param responder the HTTP response handler
   * @param id the schema id
   */
  private void listVersions(HttpServiceResponder responder, SchemaId id) {

    try {
      Set<Long> versions = registry.getVersions(id);
      ServiceResponse<Long> response = new ServiceResponse<>(versions);
      responder.sendJson(response);
    } catch (SchemaNotFoundException e) {
      notFound(responder, e.getMessage());
    } catch (SchemaRegistryException e) {
      error(responder, e.getMessage());
    }
  }
}
