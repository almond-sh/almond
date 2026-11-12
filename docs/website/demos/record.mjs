#!/usr/bin/env node
// Records the animations of the front page of the website, by typing and running the cells of
// the demo scripts of this directory (*.sc files, where "// %%" lines start cells) in a real
// JupyterLab, driven by Playwright.
//
// Frames are captured with Chrome's screencast API, along with the time they were captured at.
// Time spent waiting for the kernel with nothing changing on screen (kernel start-up, compilation,
// dependency downloads, …) is cut down to at most --max-idle milliseconds, so that recordings
// don't depend on how fast the machine they're made on is. Frames are then assembled with ffmpeg.
//
// Usage (see README.md):
//   node record.mjs --jupyter-cmd "$(./mill show dev.jupyterCmd 2.13.18 | jq -r .)" [demo.sc…]

import { chromium } from 'playwright-core';
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const demosDir = path.dirname(fileURLToPath(import.meta.url));

const { values: opts, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    'jupyter-cmd': { type: 'string' },
    port: { type: 'string', default: '8899' },
    kernel: { type: 'string', default: 'scala-debug-2.13' },
    chrome: { type: 'string' },
    ffmpeg: { type: 'string', default: 'ffmpeg' },
    out: { type: 'string', default: path.join(demosDir, '..', 'static', 'frontpage') },
    formats: { type: 'string', default: 'mp4,webm,png' },
    theme: { type: 'string', default: 'light' },
    width: { type: 'string', default: '640' },
    height: { type: 'string', default: '520' },
    scale: { type: 'string', default: '2' },
    fps: { type: 'string', default: '30' },
    'char-delay': { type: 'string', default: '45' },
    'max-idle': { type: 'string', default: '400' },
    'keep-frames': { type: 'boolean', default: false },
    help: { type: 'boolean', short: 'h', default: false },
  },
});

if (opts.help || !opts['jupyter-cmd']) {
  console.error(
    'Usage: node record.mjs --jupyter-cmd <shell command starting JupyterLab> [options] [demo.sc…]\n' +
      'Options: --port --kernel --chrome --ffmpeg --out --formats (mp4,webm,gif,png) --theme (light|dark)\n' +
      '         --width --height --scale --fps --char-delay --max-idle --keep-frames'
  );
  process.exit(opts.help ? 0 : 1);
}

const width = Number(opts.width);
const height = Number(opts.height);
const scale = Number(opts.scale);
const fps = Number(opts.fps);
const charDelay = Number(opts['char-delay']);
const maxIdle = Number(opts['max-idle']);
const formats = opts.formats.split(',').filter(Boolean);
const dark = opts.theme === 'dark';
const token = 'almond-website-demos';
const baseUrl = `http://127.0.0.1:${opts.port}`;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// Demo scripts

/** Parses a demo script: header comments, then cells, each starting with a "// %%" line, optionally
 * followed by key=value options (like "// %% pause=3000") */
function parseDemo(file) {
  const cells = [];
  let current = null;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const m = /^\/\/ %%(.*)$/.exec(line);
    if (m) {
      const options = Object.fromEntries(
        m[1].trim().split(/\s+/).filter(Boolean).map((kv) => kv.split('=', 2))
      );
      current = { lines: [], options };
      cells.push(current);
    } else if (current) current.lines.push(line);
  }
  for (const cell of cells) {
    while (cell.lines.length && !cell.lines[cell.lines.length - 1].trim()) cell.lines.pop();
    while (cell.lines.length && !cell.lines[0].trim()) cell.lines.shift();
    cell.source = cell.lines.join('\n');
  }
  return { name: path.basename(file).replace(/\.[^.]*$/, ''), cells };
}

// JupyterLab server

function findChrome() {
  if (opts.chrome) return opts.chrome;
  const cache = path.join(os.homedir(), '.cache', 'ms-playwright');
  const candidates = fs.existsSync(cache) ? fs.readdirSync(cache).sort().reverse() : [];
  for (const dir of candidates) {
    for (const rel of [
      'chrome-headless-shell-linux64/chrome-headless-shell',
      'chrome-linux64/chrome',
      'chrome-linux/chrome',
    ]) {
      const p = path.join(cache, dir, rel);
      if (fs.existsSync(p)) return p;
    }
  }
  return undefined; // let playwright find its own browser, if any
}

function writeSettings(settingsDir) {
  const settings = {
    '@jupyterlab/apputils-extension/themes': {
      theme: dark ? 'JupyterLab Dark' : 'JupyterLab Light',
      'adaptive-theme': false,
      'theme-scrollbars': true,
      overrides: { 'code-font-size': '15px', 'content-font-size1': '15px' },
    },
    '@jupyterlab/apputils-extension/notification': { checkForUpdates: false, fetchNews: 'false' },
    '@jupyterlab/completer-extension/manager': { autoCompletion: false },
    '@jupyterlab/codemirror-extension/plugin': {
      defaultConfig: { autoClosingBrackets: false, indentUnit: '2' },
    },
    '@jupyterlab/notebook-extension/tracker': {
      codeCellConfig: { autoClosingBrackets: false, lineNumbers: false, lineWrap: false },
      windowingMode: 'none',
    },
  };
  for (const [id, value] of Object.entries(settings)) {
    const file = path.join(settingsDir, `${id}.jupyterlab-settings`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, JSON.stringify(value, null, 2));
  }
}

// Front-end extensions that change what demos look like: the variable inspector runs code when
// kernels start, which makes the execution counts of the demo cells start at 2
const disabledExtensions = ['@lckr/jupyterlab_variableinspector', '@jupyter-ai/core'];

async function startJupyter(workDir) {
  const rootDir = path.join(workDir, 'notebooks');
  const settingsDir = path.join(workDir, 'settings');
  const configDir = path.join(workDir, 'config');
  fs.mkdirSync(rootDir, { recursive: true });
  writeSettings(settingsDir);
  fs.mkdirSync(path.join(configDir, 'labconfig'), { recursive: true });
  fs.writeFileSync(
    path.join(configDir, 'labconfig', 'page_config.json'),
    JSON.stringify({ disabledExtensions: Object.fromEntries(disabledExtensions.map((e) => [e, true])) })
  );
  const args = [
    '--no-browser',
    `--port=${opts.port}`,
    '--ip=127.0.0.1',
    `--IdentityProvider.token=${token}`,
    `--ServerApp.root_dir=${rootDir}`,
    '--LabApp.expose_app_in_browser=True',
    // the notebooks of the demos are deleted once recorded, they don't need to go to the trash
    '--FileContentsManager.delete_to_trash=False',
  ];
  const logFile = path.join(workDir, 'jupyter.log');
  const log = fs.openSync(logFile, 'w');
  const proc = spawn('sh', ['-c', `${opts['jupyter-cmd']} "$@"`, 'sh', ...args], {
    env: {
      ...process.env,
      JUPYTER_CONFIG_DIR: configDir,
      JUPYTERLAB_SETTINGS_DIR: settingsDir,
      JUPYTERLAB_WORKSPACES_DIR: path.join(workDir, 'workspaces'),
    },
    stdio: ['ignore', log, log],
    detached: true,
  });
  const deadline = Date.now() + 180_000;
  for (;;) {
    if (proc.exitCode !== null) throw new Error(`JupyterLab exited, see ${logFile}`);
    try {
      const resp = await fetch(`${baseUrl}/api/status?token=${token}`);
      if (resp.ok) break;
    } catch {}
    if (Date.now() > deadline) throw new Error(`JupyterLab didn't start, see ${logFile}`);
    await sleep(500);
  }
  return {
    stop: () => {
      try {
        process.kill(-proc.pid, 'SIGTERM');
      } catch {}
    },
  };
}

async function api(method, p, body) {
  const resp = await fetch(`${baseUrl}/api/${p}?token=${token}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body && JSON.stringify(body),
  });
  if (!resp.ok) throw new Error(`${method} /api/${p}: ${resp.status} ${await resp.text()}`);
  return resp.status === 204 ? null : resp.json();
}

// Recording

class Recorder {
  constructor(cdp) {
    this.cdp = cdp;
    this.frames = []; // { t, data }
    this.idleSpans = []; // [start, end]: spans where waiting on the kernel can be cut
    this.lastFrameTime = 0;
    cdp.on('Page.screencastFrame', ({ data, sessionId }) => {
      cdp.send('Page.screencastFrameAck', { sessionId }).catch(() => {});
      // Chrome sends the same frame again every 500 ms or so, even when nothing changes
      if (this.frames.length && this.frames[this.frames.length - 1].data === data) return;
      const t = performance.now();
      this.frames.push({ t, data });
      this.lastFrameTime = t;
    });
  }
  async start() {
    await this.cdp.send('Page.startScreencast', {
      format: 'png',
      maxWidth: width * scale,
      maxHeight: height * scale,
      everyNthFrame: 1,
    });
    await this.waitForQuiet(300, 5000);
    this.t0 = performance.now();
  }
  async stop() {
    this.t1 = performance.now();
    await this.cdp.send('Page.stopScreencast');
  }
  /** Waits for no new frame to have been received for `quiet` ms */
  async waitForQuiet(quiet, timeout) {
    const deadline = performance.now() + timeout;
    while (performance.now() - this.lastFrameTime < quiet && performance.now() < deadline)
      await sleep(50);
  }
  async idle(f) {
    const start = performance.now();
    try {
      return await f();
    } finally {
      this.idleSpans.push([start, performance.now()]);
    }
  }

  /** Recording time to video time, cutting idle times longer than maxIdle */
  timeline() {
    const times = this.frames.map((f) => f.t);
    const cuts = [];
    for (const [a, b] of this.idleSpans) {
      const points = [a, ...times.filter((t) => t > a && t < b), b];
      for (let i = 1; i < points.length; i++)
        if (points[i] - points[i - 1] > maxIdle) cuts.push([points[i - 1] + maxIdle, points[i]]);
    }
    return (t) => {
      let cut = 0;
      for (const [a, b] of cuts) if (t >= b) cut += b - a;
        else if (t > a) cut += t - a;
      return t - this.t0 - cut;
    };
  }

  /** Writes the frames and an ffconcat file listing them along with their durations */
  writeFrames(dir) {
    fs.mkdirSync(dir, { recursive: true });
    const warp = this.timeline();
    // the last frame before the recording start is the initial frame
    const startIdx = Math.max(0, this.frames.findLastIndex((f) => f.t <= this.t0));
    const frames = this.frames
      .slice(startIdx)
      .filter((f) => f.t <= this.t1)
      .map((f, i) => ({ ...f, vt: i === 0 ? 0 : warp(f.t) }));
    const end = warp(this.t1);
    const lines = ['ffconcat version 1.0'];
    frames.forEach((f, i) => {
      const file = `frame-${String(i).padStart(5, '0')}.png`;
      fs.writeFileSync(path.join(dir, file), Buffer.from(f.data, 'base64'));
      const next = i + 1 < frames.length ? frames[i + 1].vt : end;
      lines.push(`file '${file}'`, `duration ${(Math.max(next - f.vt, 0) / 1000).toFixed(4)}`);
    });
    // ffconcat ignores the duration of the last entry, unless the file is repeated
    lines.push(`file 'frame-${String(frames.length - 1).padStart(5, '0')}.png'`);
    fs.writeFileSync(path.join(dir, 'frames.ffconcat'), lines.join('\n') + '\n');
    return { count: frames.length, duration: end / 1000 };
  }
}

function encode(framesDir, duration, crop, outBase) {
  const input = [
    '-y', '-loglevel', 'error',
    '-f', 'concat', '-safe', '0', '-i', path.join(framesDir, 'frames.ffconcat'),
    // the last frame of the ffconcat file is listed twice, so that its duration is taken into account
    '-t', duration.toFixed(3),
  ];
  const cropF = `crop=${crop.w}:${crop.h}:${crop.x}:${crop.y}`;
  const outputs = {
    mp4: [
      '-vf', `${cropF},fps=${fps},format=yuv420p`,
      '-c:v', 'libx264', '-preset', 'veryslow', '-crf', '26', '-tune', 'animation',
      '-movflags', '+faststart', '-an',
    ],
    webm: [
      '-vf', `${cropF},fps=${fps},format=yuv420p`,
      '-c:v', 'libvpx-vp9', '-crf', '38', '-b:v', '0', '-row-mt', '1', '-an',
    ],
    gif: [
      '-vf',
      `${cropF},fps=15,scale=iw/2:-1:flags=lanczos,split[a][b];` +
        '[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle',
    ],
  };
  for (const format of formats) {
    const out = `${outBase}.${format}`;
    if (format === 'png') {
      // poster: the last frame, shown before the video starts, or instead of it
      const last = fs.readdirSync(framesDir).filter((f) => f.endsWith('.png')).sort().pop();
      run(opts.ffmpeg, ['-y', '-loglevel', 'error', '-i', path.join(framesDir, last), '-vf', cropF, out]);
    } else if (outputs[format]) run(opts.ffmpeg, [...input, ...outputs[format], out]);
    else throw new Error(`Unsupported format: ${format}`);
    console.error(`  wrote ${path.relative(process.cwd(), out)} (${(fs.statSync(out).size / 1024).toFixed(0)} KiB)`);
  }
}

function run(cmd, args) {
  const res = spawnSync(cmd, args, { stdio: 'inherit' });
  if (res.status !== 0) throw new Error(`${cmd} ${args.join(' ')} failed (${res.status ?? res.error})`);
}

// Driving JupyterLab

async function recordDemo(browser, demo, workDir) {
  const nbPath = `${demo.name}.ipynb`;
  await api('PUT', `contents/${nbPath}`, {
    type: 'notebook',
    format: 'json',
    content: {
      cells: [{ id: 'cell-0', cell_type: 'code', source: '', metadata: {}, outputs: [], execution_count: null }],
      metadata: { kernelspec: { name: opts.kernel, display_name: opts.kernel, language: 'scala' } },
      nbformat: 4,
      nbformat_minor: 5,
    },
  });

  const context = await browser.newContext({
    viewport: { width, height },
    deviceScaleFactor: scale,
    colorScheme: dark ? 'dark' : 'light',
  });
  const page = await context.newPage();
  try {
    await page.goto(`${baseUrl}/lab/tree/${nbPath}?token=${token}&reset`);
    await page.waitForFunction(
      () => {
        const w = window.jupyterapp?.shell?.currentWidget;
        const kernel = w?.sessionContext?.session?.kernel;
        return w?.content?.widgets?.length > 0 && w.sessionContext.isReady && kernel?.status === 'idle';
      },
      null,
      { timeout: 300_000, polling: 250 }
    );

    // only keep the notebook itself on screen
    await page.evaluate(async () => {
      const app = window.jupyterapp;
      app.shell.mode = 'single-document';
      app.shell.collapseLeft();
      app.shell.collapseRight();
      if (app.shell.isTopInSimpleModeVisible()) app.shell.toggleTopInSimpleModeVisibility();
      for (const side of ['left', 'right'])
        if (app.shell.isSideTabBarVisible(side)) app.shell.toggleSideTabBarVisibility(side);
      if (app.commands.isToggled('statusbar:toggle')) await app.commands.execute('statusbar:toggle');
      const w = app.shell.currentWidget;
      w.toolbar.setHidden(true);
      w.content.activeCellIndex = 0;
      w.content.mode = 'edit';
      w.content.activeCell.editor.focus();
    });
    await page.addStyleTag({
      content: `
        .cm-cursorLayer { animation: none !important; }
        .jp-cell-toolbar, .jp-Notebook-footer, .lm-DockPanel-tabBar, .jp-toast-container,
        .jp-Notebook-cellPlaceholder { display: none !important; }
      `,
    });
    await sleep(500);
    const box = await page.evaluate(() => {
      const r = window.jupyterapp.shell.currentWidget.content.node.getBoundingClientRect();
      return { x: r.x, y: r.y, w: r.width, h: r.height };
    });
    const even = (v) => 2 * Math.floor((v * scale) / 2);
    const crop = { x: even(box.x), y: even(box.y), w: even(box.w), h: even(box.h) };

    const cdp = await context.newCDPSession(page);
    const rec = new Recorder(cdp);
    await rec.start();
    await sleep(800);

    for (const [idx, cell] of demo.cells.entries()) {
      await typeCell(page, cell.source);
      const actual = await page.evaluate(
        (idx) => window.jupyterapp.shell.currentWidget.content.widgets[idx].model.sharedModel.getSource(),
        idx
      );
      if (actual !== cell.source)
        throw new Error(`Cell ${idx} of ${demo.name}: typed ${JSON.stringify(cell.source)} but got ${JSON.stringify(actual)}`);
      await sleep(300);
      await page.keyboard.press('Shift+Enter');
      await rec.idle(async () => {
        await page.waitForFunction(
          (idx) => {
            const cell = window.jupyterapp.shell.currentWidget.content.widgets[idx];
            return cell.model.executionCount !== null && cell.model.executionState !== 'running';
          },
          idx,
          { timeout: 600_000, polling: 50 }
        );
        await rec.waitForQuiet(250, 10_000);
      });
      await sleep(Number(cell.options.pause ?? 1200));
    }
    await sleep(2500);
    await rec.stop();

    const framesDir = path.join(workDir, `frames-${demo.name}${dark ? '-dark' : ''}`);
    const { count, duration } = rec.writeFrames(framesDir);
    console.error(`  ${count} frames, ${duration.toFixed(1)} s`);
    fs.mkdirSync(opts.out, { recursive: true });
    encode(framesDir, duration, crop, path.join(opts.out, `${demo.name}${dark ? '-dark' : ''}`));
    if (!opts['keep-frames']) fs.rmSync(framesDir, { recursive: true, force: true });
    else console.error(`  frames kept in ${framesDir}`);
  } finally {
    await context.close();
    // shut down the kernel of the demo, and remove its notebook
    for (const session of await api('GET', 'sessions').catch(() => []))
      if (session.path === nbPath) await api('DELETE', `sessions/${session.id}`).catch(() => {});
    await api('DELETE', `contents/${nbPath}`).catch(() => {});
  }
}

/** Types code like a human would, while working around the auto-indentation of the editor:
 * new lines and their indentation are inserted as is via the CodeMirror API (inserting a new line
 * at the end of the editor via the DOM makes Chrome add a spurious one), the rest is typed key by
 * key */
async function typeCell(page, source) {
  const lines = source.split('\n');
  for (const [i, line] of lines.entries()) {
    const indent = /^\s*/.exec(line)[0];
    if (i > 0) {
      await page.evaluate((text) => {
        const view = window.jupyterapp.shell.currentWidget.content.activeCell.editor.editor;
        view.dispatch(view.state.replaceSelection(text));
      }, '\n' + indent);
      await sleep(charDelay * 3);
    }
    for (const ch of line.slice(indent.length)) {
      await page.keyboard.type(ch);
      await sleep(charDelay * (0.6 + 0.8 * Math.random()));
    }
  }
}

// Main

const demoFiles = positionals.length
  ? positionals
  : fs.readdirSync(demosDir).filter((f) => f.endsWith('.sc')).sort().map((f) => path.join(demosDir, f));

const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'almond-website-demos-'));
console.error(`Starting JupyterLab (logs in ${workDir}/jupyter.log)`);
const server = await startJupyter(workDir);
const browser = await chromium.launch({
  executablePath: findChrome(),
  // without it, screencast frames have the size of the viewport in CSS pixels, whatever the
  // deviceScaleFactor of the browser context
  args: [`--force-device-scale-factor=${scale}`],
});
let failed = false;
try {
  for (const file of demoFiles) {
    const demo = parseDemo(file);
    console.error(`Recording ${demo.name}`);
    await recordDemo(browser, demo, workDir);
  }
} catch (e) {
  failed = true;
  console.error(e);
} finally {
  await browser.close();
  server.stop();
}
process.exit(failed ? 1 : 0);
