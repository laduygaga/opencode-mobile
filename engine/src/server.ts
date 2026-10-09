import express from "express";
import cors from "cors";
import path from "node:path";
import fs from "node:fs";
import { exec } from "node:child_process";
import { promisify } from "node:util";
import { streamText, tool } from "ai";
import { z } from "zod";
import {
  loadOpencodeConfig,
  getAgentModel,
  resolveModel,
  createModelFromResolution,
  OpencodeConfig,
} from "./config.js";

const execAsync = promisify(exec);
const app = express();
const PORT = process.env.PORT || 8080;

app.use(cors());
app.use(express.json());

let currentConfig: OpencodeConfig;

try {
  currentConfig = loadOpencodeConfig();
  console.log(`[OpenCode Engine] Loaded configuration successfully.`);
} catch (err: any) {
  console.warn(`[OpenCode Engine] Config load warning: ${err.message}`);
}

// GET /api/config
app.get("/api/config", (req, res) => {
  try {
    const configPath = (req.query.path as string) || undefined;
    const config = loadOpencodeConfig(configPath);
    res.json({ success: true, config });
  } catch (err: any) {
    res.status(500).json({ success: false, error: err.message });
  }
});

// GET /api/models
app.get("/api/models", (req, res) => {
  try {
    const config = loadOpencodeConfig();
    const availableModels: Array<{
      providerId: string;
      modelId: string;
      displayName?: string;
      fullTarget: string;
    }> = [];

    if (config.provider) {
      for (const [providerId, providerDef] of Object.entries(config.provider)) {
        if (providerDef.models) {
          for (const [modelId, modelDef] of Object.entries(providerDef.models)) {
            availableModels.push({
              providerId,
              modelId,
              displayName: modelDef.name,
              fullTarget: `${providerId}/${modelId}`,
            });
          }
        }
      }
    }

    res.json({
      success: true,
      defaultModel: config.model,
      agentOverrides: config.agent || {},
      models: availableModels,
    });
  } catch (err: any) {
    res.status(500).json({ success: false, error: err.message });
  }
});

// Built-in Execution Tools for Android OpenCode
const engineTools = {
  read_file: tool({
    description: "Read contents of a file in the workspace",
    parameters: z.object({
      filePath: z.string().describe("Relative or absolute path to the file"),
    }),
    execute: async ({ filePath }) => {
      const fullPath = path.resolve(process.cwd(), filePath);
      const content = await fs.promises.readFile(fullPath, "utf-8");
      return { filePath, content };
    },
  }),
  write_file: tool({
    description: "Write content to a file in the workspace",
    parameters: z.object({
      filePath: z.string().describe("Relative or absolute path to the file"),
      content: z.string().describe("File content to write"),
    }),
    execute: async ({ filePath, content }) => {
      const fullPath = path.resolve(process.cwd(), filePath);
      await fs.promises.mkdir(path.dirname(fullPath), { recursive: true });
      await fs.promises.writeFile(fullPath, content, "utf-8");
      return { filePath, status: "success" };
    },
  }),
  list_directory: tool({
    description: "List directory contents",
    parameters: z.object({
      dirPath: z.string().default(".").describe("Directory path"),
    }),
    execute: async ({ dirPath }) => {
      const fullPath = path.resolve(process.cwd(), dirPath);
      const files = await fs.promises.readdir(fullPath);
      return { dirPath, files };
    },
  }),
  exec_shell: tool({
    description: "Execute a shell command in the local workspace",
    parameters: z.object({
      command: z.string().describe("Shell command to run"),
    }),
    execute: async ({ command }) => {
      try {
        const { stdout, stderr } = await execAsync(command, { cwd: process.cwd() });
        return { stdout, stderr, exitCode: 0 };
      } catch (err: any) {
        return { stdout: err.stdout || "", stderr: err.message, exitCode: err.code || 1 };
      }
    },
  }),
};

// POST /api/chat
app.post("/api/chat", async (req, res) => {
  try {
    const { messages, agent, modelOverride, configPath } = req.body;

    const config = loadOpencodeConfig(configPath);
    let resolution;

    if (modelOverride) {
      resolution = resolveModel(modelOverride, config);
    } else if (agent) {
      resolution = getAgentModel(agent, config);
    } else {
      resolution = resolveModel(config.model, config);
    }

    console.log(`[Chat] Routing request to: ${resolution.fullTarget}`);
    const languageModel = createModelFromResolution(resolution);

    const result = streamText({
      model: languageModel,
      messages,
      tools: engineTools,
      maxSteps: 5,
    });

    result.pipeDataStreamToResponse(res);
  } catch (err: any) {
    console.error("[Chat Error]:", err);
    res.status(500).json({ success: false, error: err.message });
  }
});

app.listen(PORT, () => {
  console.log(`[OpenCode Engine] Server listening on http://127.0.0.1:${PORT}`);
});
