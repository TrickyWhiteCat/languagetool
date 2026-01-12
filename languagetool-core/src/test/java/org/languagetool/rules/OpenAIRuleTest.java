/*
 *  LanguageTool, a natural language style checker
 *  Copyright (C) 2024 LanguageTool Contributors
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 *  USA
 */

package org.languagetool.rules;

import org.junit.Test;
import org.languagetool.JLanguageTool;
import org.languagetool.Languages;
import org.languagetool.Language;
import org.languagetool.language.Demo;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Tests for OpenAIRule.
 */
public class OpenAIRuleTest {

  @Test
  public void testRuleCreation() {
    Language demo = new Demo();
    
    RemoteRuleConfig config = new RemoteRuleConfig();
    config.ruleId = "TEST_OPENAI_RULE";
    config.url = "http://localhost:8080/v1/chat/completions";
    config.type = "openai";
    config.options = new HashMap<>();
    config.options.put("model", "gpt-4");
    config.options.put("apiKey", "test-key");
    
    OpenAIRule rule = new OpenAIRule(demo, JLanguageTool.getMessageBundle(), config, false);
    
    assertEquals("TEST_OPENAI_RULE", rule.getId());
    assertEquals("AI-powered grammar and style checking using OpenAI-compatible API", rule.getDescription());
  }

  @Test
  public void testCreateAllWithMatchingConfig() {
    Language demo = new Demo();
    
    List<RemoteRuleConfig> configs = new ArrayList<>();
    
    RemoteRuleConfig config1 = new RemoteRuleConfig();
    config1.ruleId = "OPENAI_DEMO";
    config1.url = "http://localhost:8080/v1/chat/completions";
    config1.type = "openai";
    config1.language = "xx.*";  // Demo language short code
    config1.options = new HashMap<>();
    configs.add(config1);
    
    RemoteRuleConfig config2 = new RemoteRuleConfig();
    config2.ruleId = "GRPC_RULE";
    config2.url = "localhost:8081";
    config2.type = "grpc";
    config2.options = new HashMap<>();
    configs.add(config2);
    
    List<OpenAIRule> rules = OpenAIRule.createAll(demo, configs, false);
    
    assertEquals(1, rules.size());
    assertEquals("OPENAI_DEMO", rules.get(0).getId());
  }

  @Test
  public void testCreateAllWithNoMatchingConfig() {
    Language demo = new Demo();
    
    List<RemoteRuleConfig> configs = new ArrayList<>();
    
    RemoteRuleConfig config = new RemoteRuleConfig();
    config.ruleId = "OPENAI_EN";
    config.url = "http://localhost:8080/v1/chat/completions";
    config.type = "openai";
    config.language = "en.*";  // Won't match Demo language (xx)
    config.options = new HashMap<>();
    configs.add(config);
    
    List<OpenAIRule> rules = OpenAIRule.createAll(demo, configs, false);
    
    assertEquals(0, rules.size());
  }

  @Test
  public void testCreateAllWithNoLanguageRestriction() {
    Language demo = new Demo();
    
    List<RemoteRuleConfig> configs = new ArrayList<>();
    
    RemoteRuleConfig config = new RemoteRuleConfig();
    config.ruleId = "OPENAI_ALL";
    config.url = "http://localhost:8080/v1/chat/completions";
    config.type = "openai";
    config.language = null;  // No language restriction
    config.options = new HashMap<>();
    configs.add(config);
    
    List<OpenAIRule> rules = OpenAIRule.createAll(demo, configs, false);
    
    assertEquals(1, rules.size());
    assertEquals("OPENAI_ALL", rules.get(0).getId());
  }

  @Test
  public void testConfigTypeConstant() {
    assertEquals("openai", OpenAIRule.CONFIG_TYPE);
  }
}
