import fs from "node:fs";
import path from "node:path";
import { z } from "zod";
import { createOpenAICompatible } from "@ai-sdk/openai-compatible";
import { createGoogleGenerativeAI } from "@ai-sdk/google";
import type { LanguageModelV1 } from "ai";

export const ProviderModelSchema = z.object({
  name: z.string().optional(),
});

export const ProviderConfigSchema = z.object({
  npm: z.string(),
  name: z.string().optional(),
  options: z.record(z.any()).optional(),
  models: z.record(ProviderModelSchema).optional(),
});

export const AgentOverrideSchema = z.object({
  model: z.string(),
});

export const OpencodeConfigSchema = z.object({
  $schema: z.string().optional(),
  plugin: z.array(z.string()).optional(),
  provider: z.record(ProviderConfigSchema).optional(),
  model: z.string(),
  agent: z.record(AgentOverrideSchema).optional(),
});

export type OpencodeConfig = z.infer<typeof OpencodeConfigSchema>;
export type ProviderConfig = z.infer<typeof ProviderConfigSchema>;

export interface ModelResolution {
  providerId: string;
  modelId: string;
  providerConfig?: ProviderConfig;
  fullTarget: string;
}

/**
 * Loads and validates opencode.json configuration file.
 */
export function loadOpencodeConfig(configPath?: string): OpencodeConfig {
  const targetPath = configPath || path.join(process.cwd(), "opencode.json");

  if (!fs.existsSync(targetPath)) {
    throw new Error(`Configuration file not found at: ${targetPath}`);
  }

  const raw = fs.readFileSync(targetPath, "utf-8");
  const json = JSON.parse(raw);
  return OpencodeConfigSchema.parse(json);
}

/**
 * Resolves a model target string (e.g. "bifrost-gemini/gemini-3.6-flash") against config.
 */
export function resolveModel(
  targetString: string,
  config: OpencodeConfig
): ModelResolution {
  const parts = targetString.split("/");
  if (parts.length < 2) {
    throw new Error(
      `Invalid model format "${targetString}". Expected "providerId/modelId"`
    );
  }

  const providerId = parts[0];
  const modelId = parts.slice(1).join("/");
  const providerConfig = config.provider?.[providerId];

  return {
    providerId,
    modelId,
    providerConfig,
    fullTarget: targetString,
  };
}

/**
 * Resolves the model target for a specific subagent (e.g., explore, librarian, title).
 */
export function getAgentModel(
  agentName: string,
  config: OpencodeConfig
): ModelResolution {
  const agentOverride = config.agent?.[agentName]?.model;
  const targetString = agentOverride || config.model;
  return resolveModel(targetString, config);
}

/**
 * Dynamically instantiates Vercel AI SDK LanguageModelV1 based on provider configuration.
 */
export function createModelFromResolution(
  resolution: ModelResolution
): LanguageModelV1 {
  const { providerConfig, modelId, providerId } = resolution;

  if (!providerConfig) {
    throw new Error(`Provider "${providerId}" not configured in opencode.json`);
  }

  const npmPackage = providerConfig.npm;
  const options = providerConfig.options || {};

  if (npmPackage === "@ai-sdk/openai-compatible") {
    const provider = createOpenAICompatible({
      name: providerId,
      baseURL: options.baseURL || "http://127.0.0.1:4000/v1",
      apiKey: options.apiKey || "placeholder-token",
      headers: options.headers,
    });
    return provider(modelId);
  }

  if (npmPackage === "@ai-sdk/google") {
    const provider = createGoogleGenerativeAI({
      apiKey: options.apiKey || process.env.GOOGLE_GENERATIVE_AI_API_KEY || "",
      baseURL: options.baseURL,
    });
    return provider(modelId);
  }

  throw new Error(`Unsupported or unhandled provider npm package: ${npmPackage}`);
}
