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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.languagetool.AnalyzedSentence;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

/**
 * A remote rule that uses an OpenAI-compatible API for text correction and rewriting.
 * This rule sends text to an OpenAI-compatible endpoint (like OpenAI, Ollama, LM Studio, etc.)
 * and uses the LLM response to generate grammar corrections.
 *
 * <p>The API endpoint should be compatible with the OpenAI Chat Completions API format:
 * POST /v1/chat/completions
 *
 * <p>This rule extends {@link RemoteRule} and is fully compatible with the LanguageTool
 * extension's suggestion system. The corrections returned by this rule appear as
 * recommendations in the LanguageTool browser extension and LibreOffice/OpenOffice add-on.
 *
 * <p>Configuration options in remote-rules.json:
 * <ul>
 *   <li>{@code url} - The OpenAI-compatible API endpoint URL</li>
 *   <li>{@code ruleId} - The unique rule identifier</li>
 *   <li>{@code language} - Language code regex (e.g., "en.*" for English)</li>
 *   <li>{@code type} - Must be "openai" for this rule</li>
 *   <li>{@code options.model} - The model name (default: "gpt-4")</li>
 *   <li>{@code options.apiKey} - The API key for authentication</li>
 *   <li>{@code options.systemPrompt} - Custom system prompt for the LLM</li>
 *   <li>{@code options.thirdPartyAI} - Set to "true" if using a third-party AI service
 *       that requires user opt-in (see {@link RemoteRuleConfig#isUsingThirdPartyAI()})</li>
 *   <li>{@code options.fallbackRuleId} - ID of fallback rule when user opts out of third-party AI</li>
 * </ul>
 *
 * <p>Example configuration:
 * <pre>
 * {
 *   "ruleId": "AI_OPENAI_GRAMMAR",
 *   "type": "openai",
 *   "url": "https://api.openai.com/v1/chat/completions",
 *   "language": "en.*",
 *   "options": {
 *     "model": "gpt-4",
 *     "apiKey": "your-api-key",
 *     "thirdPartyAI": "true",
 *     "fallbackRuleId": "MORFOLOGIK_RULE_EN"
 *   }
 * }
 * </pre>
 *
 * @since 6.5
 * @see RemoteRule
 * @see RemoteRuleConfig
 */
public class OpenAIRule extends RemoteRule {

  public static final String CONFIG_TYPE = "openai";

  private static final Logger logger = LoggerFactory.getLogger(OpenAIRule.class);
  private static final int DEFAULT_TIMEOUT_MILLIS = 30000;
  private static final long DOWN_INTERVAL_MILLISECONDS = 5000;

  private static long lastRequestError = 0;

  private final ObjectMapper mapper = new ObjectMapper();
  private final String apiUrl;
  private final String apiKey;
  private final String model;
  private final String systemPrompt;

  /**
   * Creates a new OpenAI-compatible rule.
   *
   * @param language the language this rule applies to
   * @param messages the resource bundle for messages
   * @param config the remote rule configuration containing API settings
   * @param inputLogging whether to log input text
   */
  public OpenAIRule(Language language, ResourceBundle messages, RemoteRuleConfig config, boolean inputLogging) {
    super(language, messages, config, inputLogging);
    this.apiUrl = config.getUrl();
    this.apiKey = config.getOptions().getOrDefault("apiKey", "");
    this.model = config.getOptions().getOrDefault("model", "gpt-4");
    this.systemPrompt = config.getOptions().getOrDefault("systemPrompt", getDefaultSystemPrompt(language));
  }

  @NotNull
  private static String getDefaultSystemPrompt(Language language) {
    return "You are a grammar and style checker for " + language.getName() + ". " +
           "Analyze the following text and identify any grammar, spelling, punctuation, or style errors. " +
           "For each error found, respond with a JSON array of objects, where each object has: " +
           "\"offset\" (character position where error starts), " +
           "\"length\" (length of the error text), " +
           "\"message\" (description of the error), " +
           "\"replacements\" (array of suggested corrections). " +
           "If no errors are found, respond with an empty array []. " +
           "Only respond with valid JSON, no other text.";
  }

  @Override
  public String getId() {
    return serviceConfiguration.getRuleId() != null ? serviceConfiguration.getRuleId() : "AI_OPENAI_RULE";
  }

  @Override
  public String getDescription() {
    return "AI-powered grammar and style checking using OpenAI-compatible API";
  }

  protected static class OpenAIRequest extends RemoteRequest {
    final List<AnalyzedSentence> sentences;
    final String combinedText;
    final Long textSessionId;

    OpenAIRequest(List<AnalyzedSentence> sentences, String combinedText, Long textSessionId) {
      this.sentences = sentences;
      this.combinedText = combinedText;
      this.textSessionId = textSessionId;
    }
  }

  @Override
  protected RemoteRequest prepareRequest(List<AnalyzedSentence> sentences, @Nullable Long textSessionId) {
    StringBuilder sb = new StringBuilder();
    for (AnalyzedSentence sentence : sentences) {
      sb.append(sentence.getText());
    }
    return new OpenAIRequest(sentences, sb.toString(), textSessionId);
  }

  @Override
  protected Callable<RemoteRuleResult> executeRequest(RemoteRequest requestArg, long timeoutMilliseconds) throws TimeoutException {
    return () -> {
      OpenAIRequest request = (OpenAIRequest) requestArg;
      
      // Basic health check - mark server as down after an error for given interval
      if (System.currentTimeMillis() - lastRequestError < DOWN_INTERVAL_MILLISECONDS) {
        logger.warn("Temporarily disabled OpenAI server because of recent error.");
        return fallbackResults(request);
      }

      try {
        List<RuleMatch> matches = callOpenAIApi(request.combinedText, request.sentences, timeoutMilliseconds);
        return new RemoteRuleResult(true, true, matches, request.sentences);
      } catch (Exception e) {
        lastRequestError = System.currentTimeMillis();
        logger.warn("Failed to query OpenAI-compatible server at {}: {}", apiUrl, e.getMessage());
        return fallbackResults(request);
      }
    };
  }

  @Override
  protected RemoteRuleResult fallbackResults(RemoteRequest request) {
    OpenAIRequest req = (OpenAIRequest) request;
    return new RemoteRuleResult(false, false, Collections.emptyList(), req.sentences);
  }

  private List<RuleMatch> callOpenAIApi(String text, List<AnalyzedSentence> sentences, long timeoutMilliseconds) throws IOException {
    URL url = new URL(apiUrl);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    
    try {
      int timeout = timeoutMilliseconds > 0 ? (int) timeoutMilliseconds : DEFAULT_TIMEOUT_MILLIS;
      conn.setConnectTimeout(timeout);
      conn.setReadTimeout(timeout);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json");
      
      if (apiKey != null && !apiKey.isEmpty()) {
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
      }
      
      conn.setDoOutput(true);
      
      // Build the request body
      String requestBody = buildRequestBody(text);
      
      try (OutputStream os = conn.getOutputStream()) {
        byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
        os.write(input, 0, input.length);
      }
      
      int responseCode = conn.getResponseCode();
      if (responseCode != 200) {
        String errorBody = "";
        try (InputStream errorStream = conn.getErrorStream()) {
          if (errorStream != null) {
            errorBody = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
          }
        }
        throw new IOException("OpenAI API returned status " + responseCode + ": " + errorBody);
      }
      
      try (InputStream is = conn.getInputStream()) {
        return parseResponse(is, sentences);
      }
    } finally {
      conn.disconnect();
    }
  }

  private String buildRequestBody(String text) throws IOException {
    Map<String, Object> requestMap = new HashMap<>();
    requestMap.put("model", model);
    
    List<Map<String, String>> messages = new ArrayList<>();
    
    Map<String, String> systemMessage = new HashMap<>();
    systemMessage.put("role", "system");
    systemMessage.put("content", systemPrompt);
    messages.add(systemMessage);
    
    Map<String, String> userMessage = new HashMap<>();
    userMessage.put("role", "user");
    userMessage.put("content", text);
    messages.add(userMessage);
    
    requestMap.put("messages", messages);
    requestMap.put("temperature", 0.0); // Deterministic output for grammar checking
    
    return mapper.writeValueAsString(requestMap);
  }

  private List<RuleMatch> parseResponse(InputStream is, List<AnalyzedSentence> sentences) throws IOException {
    OpenAIResponse response = mapper.readValue(is, OpenAIResponse.class);
    
    if (response.choices == null || response.choices.isEmpty()) {
      return Collections.emptyList();
    }
    
    String content = response.choices.get(0).message.content;
    if (content == null || content.trim().isEmpty()) {
      return Collections.emptyList();
    }
    
    // Parse the JSON response containing error corrections
    return parseCorrections(content, sentences);
  }

  private List<RuleMatch> parseCorrections(String jsonContent, List<AnalyzedSentence> sentences) {
    List<RuleMatch> matches = new ArrayList<>();
    
    try {
      // Clean up the response - remove markdown code blocks if present
      String cleanJson = jsonContent.trim();
      if (cleanJson.startsWith("```json")) {
        cleanJson = cleanJson.substring(7);
      } else if (cleanJson.startsWith("```")) {
        cleanJson = cleanJson.substring(3);
      }
      if (cleanJson.endsWith("```")) {
        cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
      }
      cleanJson = cleanJson.trim();
      
      if (cleanJson.isEmpty() || cleanJson.equals("[]")) {
        return matches;
      }
      
      List<Map<String, Object>> corrections = mapper.readValue(cleanJson, 
          mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
      
      // Build a map of sentence offsets
      int[] sentenceOffsets = new int[sentences.size()];
      int currentOffset = 0;
      for (int i = 0; i < sentences.size(); i++) {
        sentenceOffsets[i] = currentOffset;
        currentOffset += sentences.get(i).getText().length();
      }
      
      for (Map<String, Object> correction : corrections) {
        try {
          int offset = ((Number) correction.get("offset")).intValue();
          int length = ((Number) correction.get("length")).intValue();
          String message = (String) correction.get("message");
          @SuppressWarnings("unchecked")
          List<String> replacements = (List<String>) correction.get("replacements");
          
          // Find the sentence this correction belongs to
          AnalyzedSentence matchingSentence = null;
          int sentenceOffset = 0;
          for (int i = 0; i < sentences.size(); i++) {
            int sentenceStart = sentenceOffsets[i];
            int sentenceEnd = sentenceStart + sentences.get(i).getText().length();
            if (offset >= sentenceStart && offset < sentenceEnd) {
              matchingSentence = sentences.get(i);
              sentenceOffset = sentenceStart;
              break;
            }
          }
          
          if (matchingSentence == null) {
            continue;
          }
          
          // Adjust offset relative to sentence
          int localOffset = offset - sentenceOffset;
          
          RuleMatch match = new RuleMatch(
              this,
              matchingSentence,
              localOffset,
              localOffset + length,
              message
          );
          
          if (replacements != null && !replacements.isEmpty()) {
            match.setSuggestedReplacements(replacements);
          }
          
          matches.add(match);
        } catch (Exception e) {
          logger.warn("Failed to parse correction: {}", correction, e);
        }
      }
    } catch (Exception e) {
      logger.warn("Failed to parse OpenAI response as JSON: {}", jsonContent, e);
    }
    
    return matches;
  }

  /**
   * Factory method to create OpenAI rules from configuration.
   *
   * @param language the language for the rule
   * @param configs list of remote rule configurations
   * @param inputLogging whether to log input
   * @return list of OpenAI rules matching the configuration
   */
  public static List<OpenAIRule> createAll(Language language, List<RemoteRuleConfig> configs, boolean inputLogging) {
    List<OpenAIRule> rules = new ArrayList<>();
    for (RemoteRuleConfig config : configs) {
      if (CONFIG_TYPE.equals(config.getType()) && 
          (config.getLanguage() == null || language.getShortCodeWithCountryAndVariant().matches(config.getLanguage()))) {
        rules.add(new OpenAIRule(language, JLanguageTool.getMessageBundle(), config, inputLogging));
      }
    }
    return rules;
  }

  // Jackson DTOs for OpenAI API response
  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class OpenAIResponse {
    public List<Choice> choices;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class Choice {
    public Message message;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class Message {
    public String role;
    public String content;
  }
}
