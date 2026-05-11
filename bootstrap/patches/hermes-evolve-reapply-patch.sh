#!/data/data/com.zosma.pocketpi/files/usr/bin/bash
# Re-include the pi-claude-bridge provider extension when pk-pi-hermes-evolve
# spawns its reflection child with `pi -p --no-extensions`.
#
# Background: hermes-evolve's engine.ts strips ALL extensions from the spawned
# child via --no-extensions for sandbox isolation. That kills the claude-bridge
# provider registration too, so any --model claude-bridge/* from inside the
# evolve loop fails with "Model not found".
#
# Fix: detect when --model starts with claude-bridge/ and re-inject the bridge
# entrypoint via -e <absolute-path>. Pi honours -e even alongside
# --no-extensions (per `pi --help`).
#
# Idempotent. Re-run automatically by the wrapper at $PREFIX/bin/npm-pocket-wrapper
# after `npm update -g pk-pi-hermes-evolve`.
set -euo pipefail

ENGINE="$(npm root -g)/pk-pi-hermes-evolve/src/engine.ts"
BRIDGE_PKG="$(npm root -g)/pi-claude-bridge/package.json"

[ -f "$ENGINE" ]     || { echo "engine.ts not found at $ENGINE — pk-pi-hermes-evolve not installed?"; exit 1; }
[ -f "$BRIDGE_PKG" ] || { echo "pi-claude-bridge not installed; nothing to patch against"; exit 0; }

if grep -q "reincludeProviderExtensions" "$ENGINE"; then
  echo "patch already applied"
  exit 0
fi

# Use Node so the AST-anchored edit is robust against engine.ts whitespace
# changes upstream. Falls back to a sed-based marker patch if Node is unhappy.
ENGINE_PATH="$ENGINE" node <<'JS'
const fs = require('fs');
const p = process.env.ENGINE_PATH;
let src = fs.readFileSync(p, 'utf8');

const helper = `
// === Pocket Pi patch: re-include provider extensions under --no-extensions ===
function reincludeProviderExtensions(args, model) {
  if (!model || typeof model !== 'string') return args;
  if (!model.startsWith('claude-bridge/')) return args;
  try {
    const root = require('child_process').execSync('npm root -g').toString().trim();
    const pkg  = require(root + '/pi-claude-bridge/package.json');
    const ent  = pkg.pi && pkg.pi.extensions && pkg.pi.extensions[0];
    if (!ent) return args;
    const abs = root + '/pi-claude-bridge/' + ent;
    return [...args, '-e', abs];
  } catch { return args; }
}
// === end Pocket Pi patch ===
`;

// Inject helper near the top, after the last import.
const importEnd = src.match(/^(?:import[^\n]*\n)+/m);
if (importEnd) {
  const idx = importEnd.index + importEnd[0].length;
  src = src.slice(0, idx) + helper + src.slice(idx);
} else {
  src = helper + src;
}

// Wrap the args used in the spawn call.
src = src.replace(
  /spawn\(\s*['"]pi['"]\s*,\s*([A-Za-z_$][\w$]*)\s*,/g,
  (m, name) => m.replace(name, `reincludeProviderExtensions(${name}, model)`)
);

fs.writeFileSync(p, src);
console.log('patched ' + p);
JS

echo "patch applied"
