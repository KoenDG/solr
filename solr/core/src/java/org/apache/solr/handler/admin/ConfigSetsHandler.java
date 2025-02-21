/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler.admin;

import static org.apache.solr.common.params.CommonParams.NAME;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import org.apache.solr.api.Api;
import org.apache.solr.api.JerseyResource;
import org.apache.solr.client.api.model.CloneConfigsetRequestBody;
import org.apache.solr.client.api.model.SolrJerseyResponse;
import org.apache.solr.cloud.ConfigSetCmds;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.params.ConfigSetParams;
import org.apache.solr.common.params.ConfigSetParams.ConfigSetAction;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.handler.api.V2ApiUtils;
import org.apache.solr.handler.configsets.CloneConfigSet;
import org.apache.solr.handler.configsets.ConfigSetAPIBase;
import org.apache.solr.handler.configsets.DeleteConfigSet;
import org.apache.solr.handler.configsets.ListConfigSets;
import org.apache.solr.handler.configsets.UploadConfigSet;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.PermissionNameProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A {@link org.apache.solr.request.SolrRequestHandler} for ConfigSets API requests. */
public class ConfigSetsHandler extends RequestHandlerBase implements PermissionNameProvider {
  // TODO refactor into o.a.s.handler.configsets package to live alongside actual API logic
  public static final Boolean DISABLE_CREATE_AUTH_CHECKS =
      Boolean.getBoolean("solr.disableConfigSetsCreateAuthChecks"); // this is for back compat only
  public static final String DEFAULT_CONFIGSET_NAME = "_default";
  public static final String AUTOCREATED_CONFIGSET_SUFFIX = ".AUTOCREATED";
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  protected final CoreContainer coreContainer;
  public static long CONFIG_SET_TIMEOUT = 300 * 1000;

  /**
   * Overloaded ctor to inject CoreContainer into the handler.
   *
   * @param coreContainer Core Container of the solr webapp installed.
   */
  public ConfigSetsHandler(final CoreContainer coreContainer) {
    this.coreContainer = coreContainer;
  }

  public static String getSuffixedNameForAutoGeneratedConfigSet(String configName) {
    return configName + AUTOCREATED_CONFIGSET_SUFFIX;
  }

  public static boolean isAutoGeneratedConfigSet(String configName) {
    return configName != null && configName.endsWith(AUTOCREATED_CONFIGSET_SUFFIX);
  }

  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    checkErrors();

    // Pick the action
    final SolrParams requiredSolrParams = req.getParams().required();
    final String actionStr = requiredSolrParams.get(ConfigSetParams.ACTION);
    ConfigSetAction action = ConfigSetAction.get(actionStr);
    if (action == null) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Unknown action: " + actionStr);
    }

    switch (action) {
      case DELETE:
        final DeleteConfigSet deleteConfigSetAPI = new DeleteConfigSet(coreContainer, req, rsp);
        final var deleteResponse =
            deleteConfigSetAPI.deleteConfigSet(req.getParams().required().get(NAME));
        V2ApiUtils.squashIntoSolrResponseWithoutHeader(rsp, deleteResponse);
        break;
      case UPLOAD:
        final var uploadApi = new UploadConfigSet(coreContainer, req, rsp);
        final var configSetName = req.getParams().required().get(NAME);
        final var overwrite = req.getParams().getBool(ConfigSetParams.OVERWRITE, false);
        final var cleanup = req.getParams().getBool(ConfigSetParams.CLEANUP, false);
        final var configSetData = ConfigSetAPIBase.ensureNonEmptyInputStream(req);
        SolrJerseyResponse uploadResponse;
        if (req.getParams()
            .get(ConfigSetParams.FILE_PATH, "")
            .isEmpty()) { // Uploading a whole configset
          uploadResponse =
              uploadApi.uploadConfigSet(configSetName, overwrite, cleanup, configSetData);
        } else { // Uploading a single file
          final var filePath = req.getParams().get(ConfigSetParams.FILE_PATH);
          uploadResponse =
              uploadApi.uploadConfigSetFile(
                  configSetName, filePath, overwrite, cleanup, configSetData);
        }
        V2ApiUtils.squashIntoSolrResponseWithoutHeader(rsp, uploadResponse);
        break;
      case LIST:
        final ListConfigSets listConfigSetsAPI = new ListConfigSets(coreContainer);
        V2ApiUtils.squashIntoSolrResponseWithoutHeader(rsp, listConfigSetsAPI.listConfigSet());
        break;
      case CREATE:
        final String newConfigSetName = req.getParams().get(NAME);
        if (newConfigSetName == null || newConfigSetName.length() == 0) {
          throw new SolrException(ErrorCode.BAD_REQUEST, "ConfigSet name not specified");
        }

        // Map v1 parameters into v2 format and process request
        final var requestBody = new CloneConfigsetRequestBody();
        requestBody.name = newConfigSetName;
        if (req.getParams().get(ConfigSetCmds.BASE_CONFIGSET) != null) {
          requestBody.baseConfigSet = req.getParams().get(ConfigSetCmds.BASE_CONFIGSET);
        } else {
          requestBody.baseConfigSet = "_default";
        }
        requestBody.properties = new HashMap<>();
        req.getParams().stream()
            .filter(entry -> entry.getKey().startsWith(ConfigSetCmds.CONFIG_SET_PROPERTY_PREFIX))
            .forEach(
                entry -> {
                  final String newKey =
                      entry.getKey().substring(ConfigSetCmds.CONFIG_SET_PROPERTY_PREFIX.length());
                  final Object value =
                      (entry.getValue().length == 1) ? entry.getValue()[0] : entry.getValue();
                  requestBody.properties.put(newKey, value);
                });
        final CloneConfigSet createConfigSetAPI = new CloneConfigSet(coreContainer, req, rsp);
        final var createResponse = createConfigSetAPI.cloneExistingConfigSet(requestBody);
        V2ApiUtils.squashIntoSolrResponseWithoutHeader(rsp, createResponse);
        break;
      default:
        throw new IllegalStateException("Unexpected ConfigSetAction detected: " + action);
    }
    rsp.setHttpCaching(false);
  }

  protected void checkErrors() {
    if (coreContainer == null) {
      throw new SolrException(ErrorCode.BAD_REQUEST, "Core container instance missing");
    }

    // Make sure that the core is ZKAware
    if (!coreContainer.isZooKeeperAware()) {
      throw new SolrException(
          ErrorCode.BAD_REQUEST, "Solr instance is not running in SolrCloud mode.");
    }
  }

  @Override
  public String getDescription() {
    return "Manage SolrCloud ConfigSets";
  }

  @Override
  public Category getCategory() {
    return Category.ADMIN;
  }

  @Override
  public Boolean registerV2() {
    return true;
  }

  @Override
  public Collection<Api> getApis() {
    return new ArrayList<>();
  }

  @Override
  public Collection<Class<? extends JerseyResource>> getJerseyResources() {
    return List.of(
        ListConfigSets.class, CloneConfigSet.class, DeleteConfigSet.class, UploadConfigSet.class);
  }

  @Override
  public Name getPermissionName(AuthorizationContext ctx) {
    String a = ctx.getParams().get(ConfigSetParams.ACTION);
    if (a != null) {
      ConfigSetAction action = ConfigSetAction.get(a);
      if (action == ConfigSetAction.CREATE
          || action == ConfigSetAction.DELETE
          || action == ConfigSetAction.UPLOAD) {
        return Name.CONFIG_EDIT_PERM;
      } else if (action == ConfigSetAction.LIST) {
        return Name.CONFIG_READ_PERM;
      }
    }
    return null;
  }
}
