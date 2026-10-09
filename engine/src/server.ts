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

// GET /api/plugins
app.get("/api/plugins", (req, res) => {
  try {
    const config = loadOpencodeConfig();
    const installedPlugins = config.plugin || [];

    const availableModes = [
      { id: "standard", label: "Standard", description: "Default assistant interaction mode" },
      { id: "ultrawork", label: "Ultrawork", description: "Continuous execution until completion with todo tracking" },
      { id: "architect", label: "Architect", description: "Metis plan consultant & multi-agent system design mode" },
      { id: "deep-research", label: "Deep Research", description: "Exhaustive multi-perspective codebase & docs research" }
    ];

    const availableAgents = [
      "explore", "librarian", "scout", "summary",
      "oracle", "metis", "momus", "artistry",
      "visual-engineering", "ultrabrain", "deep", "quick", "writing"
    ];

    res.json({
      success: true,
      plugins: installedPlugins,
      modes: availableModes,
      agents: availableAgents
    });
  } catch (err: any) {
    res.status(500).json({ success: false, error: err.message });
  }
});

// POST /api/plugins/install
app.post("/api/plugins/install", async (req, res) => {
  try {
    const { packageName, configPath } = req.body;
    if (!packageName || typeof packageName !== "string") {
      return res.status(400).json({ success: false, error: "Missing packageName field" });
    }

    const targetConfigPath = configPath || path.join(process.cwd(), "opencode.json");
    const raw = fs.readFileSync(targetConfigPath, "utf-8");
    const json = JSON.parse(raw);

    if (!Array.isArray(json.plugin)) {
      json.plugin = [];
    }

    if (!json.plugin.includes(packageName)) {
      json.plugin.push(packageName);
      fs.writeFileSync(targetConfigPath, JSON.stringify(json, null, 2), "utf-8");
    }

    console.log(`[Plugin Install] Installing package ${packageName}...`);
    const { stdout, stderr } = await execAsync(`npm install ${packageName}`, { cwd: process.cwd() });

    res.json({ success: true, installed: packageName, stdout, stderr });
  } catch (err: any) {
    console.error("[Plugin Install Error]:", err);
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
    const { messages, agent, mode, modelOverride, configPath } = req.body;

    const config = loadOpencodeConfig(configPath);
    let resolution;

    if (modelOverride) {
      resolution = resolveModel(modelOverride, config);
    } else if (agent) {
      resolution = getAgentModel(agent, config);
    } else {
      resolution = resolveModel(config.model, config);
    }

    console.log(`[Chat] Routing request to: ${resolution.fullTarget} | Agent: ${agent || "default"} | Mode: ${mode || "standard"}`);
    const languageModel = createModelFromResolution(resolution);

    let systemPrompt = "";
    if (mode === "ultrawork" || mode === "ultraworker") {
      systemPrompt = "[MODE: ULTRAWORK / RALPH LOOP ACTIVATED]\nExecute continuously until complete. Track tasks obsessively. Do not yield prematurely until goal is fully met.";
    } else if (mode === "architect") {
      systemPrompt = "[MODE: ARCHITECT / METIS PLAN CONSULTANT ACTIVATED]\nAnalyze implicit intent, structure step-by-step breakdown, identify risks and trade-offs before execution.";
    } else if (mode === "deep-research") {
      systemPrompt = "[MODE: DEEP RESEARCH ACTIVATED]\nPerform parallel deep search and multi-perspective investigation across codebase and documentation.";
    }

    const result = streamText({
      model: languageModel,
      system: systemPrompt || undefined,
      messages,
      tools: engineTools,
      maxSteps: mode === "ultrawork" ? 15 : 5,
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
