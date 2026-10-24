#!/usr/bin/env node
// Converts Docusaurus 1 versioned docs to the Docusaurus 3 format, in place
//
// Usage: convert-v1-versioned-docs.js <website-dir>
//
// Docusaurus 1 only saved the docs and sidebar that changed in each version, and
// prefixed ids with the version ("version-0.15.0-intro"). Docusaurus 3 needs every doc
// and the sidebar of each version, with unprefixed ids. Versions that are already in
// the Docusaurus 3 format are left untouched, so this can be run more than once.

const fs = require('fs');
const path = require('path');

const websiteDir = process.argv[2];
if (!websiteDir) {
  console.error('Usage: convert-v1-versioned-docs.js <website-dir>');
  process.exit(1);
}

const versionsFile = path.join(websiteDir, 'versions.json');
if (!fs.existsSync(versionsFile)) {
  console.log(`No ${versionsFile}, nothing to convert`);
  process.exit(0);
}
// newest version first
const versions = JSON.parse(fs.readFileSync(versionsFile, 'utf8'));
const docsDir = (version) => path.join(websiteDir, 'versioned_docs', `version-${version}`);
const sidebarFile = (version) =>
  path.join(websiteDir, 'versioned_sidebars', `version-${version}-sidebars.json`);

const v1IdLine = /^(id: *version-.*|original_id: .*)\n/gm;
const isV1Doc = (content) => /^original_id:/m.test(content);
const readSidebar = (version) =>
  fs.existsSync(sidebarFile(version)) ? JSON.parse(fs.readFileSync(sidebarFile(version), 'utf8')) : null;
const isV1Sidebar = (sidebar, version) =>
  Object.keys(sidebar).some((key) => key.startsWith(`version-${version}-`));

// Read everything first, so that later versions don't fall back on already converted files
const docs = new Map(
  versions.map((version) => {
    const dir = docsDir(version);
    const entries = fs.existsSync(dir)
      ? fs.readdirSync(dir).filter((name) => name.endsWith('.md'))
      : [];
    return [version, new Map(entries.map((name) => [name, fs.readFileSync(path.join(dir, name), 'utf8')]))];
  }),
);
const sidebars = new Map(versions.map((version) => [version, readSidebar(version)]));

versions.forEach((version, idx) => {
  const ownDocs = docs.get(version);
  const ownSidebar = sidebars.get(version);
  const isV1 =
    [...ownDocs.values()].some(isV1Doc) || (ownSidebar !== null && isV1Sidebar(ownSidebar, version));
  if (!isV1 && ownSidebar !== null) return;

  // older versions, starting with this one
  const fallbacks = versions.slice(idx);

  const allDocs = new Map();
  for (const fallback of fallbacks.slice().reverse())
    for (const [name, content] of docs.get(fallback)) allDocs.set(name, content);
  fs.mkdirSync(docsDir(version), {recursive: true});
  for (const [name, content] of allDocs)
    fs.writeFileSync(path.join(docsDir(version), name), content.replace(v1IdLine, ''));

  const fallbackSidebarVersion = fallbacks.find((fallback) => sidebars.get(fallback) !== null);
  if (fallbackSidebarVersion === undefined)
    throw new Error(`No sidebar found for version ${version}`);
  const prefix = `version-${fallbackSidebarVersion}-`;
  const unprefix = (id) => (id.startsWith(prefix) ? id.slice(prefix.length) : id);
  const sidebar = {};
  for (const [sidebarId, categories] of Object.entries(sidebars.get(fallbackSidebarVersion))) {
    const newCategories = {};
    for (const [category, ids] of Object.entries(categories)) newCategories[category] = ids.map(unprefix);
    sidebar[unprefix(sidebarId)] = newCategories;
  }
  fs.mkdirSync(path.dirname(sidebarFile(version)), {recursive: true});
  fs.writeFileSync(sidebarFile(version), JSON.stringify(sidebar, null, 2) + '\n');

  console.log(`Converted docs of version ${version}`);
});
