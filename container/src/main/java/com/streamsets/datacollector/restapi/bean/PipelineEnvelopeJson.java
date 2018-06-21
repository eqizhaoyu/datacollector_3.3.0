/*
 * Copyright 2017 StreamSets Inc.
 *
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
package com.streamsets.datacollector.restapi.bean;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * In order to write a composite data property (definitions) out to JSON without reading
 * it back in, we need to explicitly ignore the property, as well as the setter and
 * then apply the @JsonProperty annotation to the getter.
 **/
public class PipelineEnvelopeJson {
  private PipelineConfigurationJson pipelineConfig;
  private RuleDefinitionsJson pipelineRules;
  @JsonIgnore
  private DefinitionsJson libraryDefinitions;

  public PipelineConfigurationJson getPipelineConfig() {
    return pipelineConfig;
  }

  public void setPipelineConfig(PipelineConfigurationJson pipelineConfig) {
    this.pipelineConfig = pipelineConfig;
  }

  public RuleDefinitionsJson getPipelineRules() {
    return pipelineRules;
  }

  public void setPipelineRules(RuleDefinitionsJson pipelineRules) {
    this.pipelineRules = pipelineRules;
  }

  @JsonProperty("libraryDefinitions")
  public DefinitionsJson getLibraryDefinitions() {
    return libraryDefinitions;
  }

  @JsonIgnore
  public void setLibraryDefinitions(DefinitionsJson libraryDefinitions) {
    this.libraryDefinitions = libraryDefinitions;
  }
}
