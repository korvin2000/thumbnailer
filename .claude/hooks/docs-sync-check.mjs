// Stop hook: blocks the stop once when uncommitted code/config changes are newer than every doc file,
// asking Claude to apply .claude/rules/docs-sync.md. Fires again only after further code changes.
// Never fails the stop: any error → silent exit 0.
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';

const CODE = [
  /^(backend|frontend)\/src\//,
  /(^|\/)(build|settings)\.gradle\.kts$/,
  /^frontend\/(package\.json|angular\.json|proxy\.conf\.json|tsconfig[^/]*\.json)$/,
  /^\.claude\/launch\.json$/,
];

try {
  const input = JSON.parse(readFileSync(0, 'utf8') || '{}');
  if (input.stop_hook_active) process.exit(0);
  const root = process.env.CLAUDE_PROJECT_DIR || process.cwd();

  // git status -z: "XY path\0", renames/copies add "\0old"; deleted files are dated by their directory.
  const out = execFileSync('git', ['status', '--porcelain', '-uall', '-z'], { cwd: root, encoding: 'utf8' });
  const tokens = out.split('\0');
  const changed = [];
  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i];
    if (t.length < 4) continue;
    if ('RC'.includes(t[0]) || 'RC'.includes(t[1])) i++;
    changed.push(t.slice(3));
  }
  const mtime = p => { const f = join(root, p); return existsSync(f) ? statSync(f).mtimeMs : existsSync(dirname(f)) ? statSync(dirname(f)).mtimeMs : 0; };
  const code = changed.filter(p => CODE.some(r => r.test(p))).map(p => ({ p, t: mtime(p) }));
  if (!code.length) process.exit(0);

  const docs = [];
  const walk = d => existsSync(join(root, d)) && readdirSync(join(root, d), { withFileTypes: true })
    .forEach(e => e.isDirectory() ? walk(join(d, e.name)) : e.name.endsWith('.md') && docs.push(join(d, e.name)));
  readdirSync(root).filter(f => f.endsWith('.md')).forEach(f => docs.push(f));
  ['docs', '.claude/rules'].forEach(walk);
  ['backend/CLAUDE.md', 'frontend/CLAUDE.md'].forEach(f => docs.push(f));
  const newestDoc = Math.max(0, ...docs.map(mtime));

  const stale = code.filter(c => c.t > newestDoc).sort((a, b) => b.t - a.t);
  if (!stale.length) process.exit(0);

  // Remind once per change set: skip if nothing changed since the last reminder in this session.
  const state = join(tmpdir(), `claude-docs-sync-${(input.session_id || 'none').replace(/\W/g, '')}.json`);
  const newest = stale[0].t;
  if (existsSync(state) && JSON.parse(readFileSync(state, 'utf8')).newest >= newest) process.exit(0);
  writeFileSync(state, JSON.stringify({ newest }));

  const list = stale.slice(0, 12).map(c => `- ${c.p}`).join('\n') + (stale.length > 12 ? `\n- … ${stale.length - 12} more` : '');
  console.log(JSON.stringify({
    decision: 'block',
    reason: `Docs sync check: these uncommitted code/config files changed after the docs were last updated:\n${list}\n` +
      'Apply .claude/rules/docs-sync.md now (docs/map/*.md, SPEC.md, docs/ARCHITECTURE.md, README.md, CLAUDE.md files, PLAN.md as applicable), ' +
      'or state in your reply why no doc change is needed. This reminder fires once per change set.',
  }));
} catch {
  process.exit(0);
}
