import { readFile, writeFile, mkdir, cp, rm } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { rolldown } from 'rolldown';
import { readAllConfig } from '../dshgit/dsh-pet/src/host/config/read.ts';
import { chatActionNames, actionInstruction } from '../dshgit/dsh-pet/src/host/chat-action.ts';
import { readMemePool, limitPool, memeCatalog } from '../dshgit/dsh-pet/src/host/memes.ts';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const upstream = join(root, 'dshgit/dsh-pet');
const runtime = join(upstream, 'runtime/electron-helper');
const out = join(root, 'app/build/generated/petAssets/pet');
const manifest = JSON.parse(await readFile(join(root, 'android/upstream-tree.json'), 'utf8'));

// Fail on altered original engine or media, rather than silently shipping a fork.
const checked = manifest.tree.filter(item =>
  item.path.startsWith('dsh-pet/src/shared/') ||
  item.path.startsWith('dsh-pet/runtime/electron-helper/') ||
  item.path.startsWith('dsh-pet/assets/webm/') ||
  item.path === 'dsh-pet/assets/config.jsonc' ||
  item.path === 'dsh-pet/src/host/sleep.ts' ||
  item.path === 'dsh-pet/src/host/whisper.ts' ||
  item.path === 'dsh-pet/src/host/chat-action.ts' ||
  item.path === 'dsh-pet/src/host/memes.ts' ||
  item.path === 'dsh-pet/src/client/pet/motion/touch-press.ts');
for (const item of checked) {
  const content = await readFile(join(root, 'dshgit', item.path));
  const hash = createHash('sha1').update(Buffer.from('blob '+content.length+'\0')).update(content).digest('hex');
  if (hash !== item.sha) throw new Error('Original source changed: '+item.path);
}
await rm(out, {recursive:true,force:true});
await mkdir(out, {recursive:true});
for (const filename of ['constants.js','sprite.js','events.js'])
  await cp(join(runtime, filename), join(out, filename));
await cp(join(runtime, 'sprite'), join(out, 'sprite'), {recursive:true});
await cp(join(upstream, 'assets'), join(out, 'assets'), {
  recursive:true,filter:source => !source.includes('/preview/'),
});
for (const filename of ['prelude.js','offline.js','mobile.css'])
  await cp(join(root, 'android/pet', filename), join(out, filename));

for (const [input, name, file] of [
  [join(upstream,'src/shared/index.ts'), 'PetShared', 'shared-core.js'],
  [join(root,'android/pet/helpers.ts'), 'PetOffline', 'helpers.js'],
]) {
  const bundle = await rolldown({input,platform:'browser'});
  await bundle.write({format:'iife',name,file:join(out,file)});
  await bundle.close();
}
const config = readAllConfig({
  defaultFile:join(upstream,'assets/config.jsonc'),
  userFile:join(root,'android/.no-user-config/main-config.jsonc'),
  petDir:join(root,'android/.no-user-config/pet'),
});
await writeFile(join(out,'config.json'), JSON.stringify(config));
// Instructions and candidate lists come from the pinned upstream version.
const whisperSource = await readFile(join(upstream,'src/host/whisper.ts'), 'utf8');
const instruction = name => {
  const match = whisperSource.match(new RegExp('const '+name+" = '([^'\\n]+)';"));
  if (!match) throw new Error('Upstream companion instruction changed: '+name);
  return match[1];
};
const main = config.main;
const actions = main.chatAnimationEnabled ? chatActionNames(main.animations) : [];
const memes = readMemePool(main.memes, [join(upstream,'assets/memes')]);
const chatMemes = main.chatImageEnabled ? limitPool(memes, main.chatImageLimit) : [];
await writeFile(join(out,'companion.json'), JSON.stringify({
  persona:main.whisperPrompt, whisper:instruction('USER_TEXT'), dream:instruction('SLEEP_TALK_TEXT'),
  whisperSec:main.eventsRefreshSec?.whisper || 300, dreamSec:main.eventsRefreshSec?.sleepTalk || 60,
  memes:main.whisperImageEnabled ? memes : [], actions, chatMemes,
  decorationInstruction:(chatMemes.length ? '\n\n[配图] 回复结尾可选附一张表情包给用户看，从下列清单里挑最贴合当前语境的：\n'+
    memeCatalog(chatMemes)+'\n挑中就在回复最后另起一行写 [图:名称]（名称原样照抄）；没有合适的就完全不要写这个标记。' : '')+
    (actions.length ? actionInstruction(actions) : ''),
}));

// Keep upstream source untouched. Only these two boot lines differ in the APK:
// native settings choose mobile scale; offline boot does not start host polling.
const originalBoot = await readFile(join(runtime,'renderer.js'), 'utf8');
if (!originalBoot.includes('const pet = pets[CONFIG.petIndex];') ||
    originalBoot.split('startLoops();').length !== 2) throw new Error('Upstream boot contract changed');
const boot = originalBoot.replace('const pet = pets[CONFIG.petIndex];',
  'const pet = window.PetAndroid.mobilePet(pets);')
  .replace('startLoops();','window.PetAndroid.onReady();');
await writeFile(join(out,'renderer.js'), boot);
let html = await readFile(join(runtime,'index.html'), 'utf8');
html = html.replace('<meta charset="utf-8" />',
  '<meta charset="utf-8" /><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1" />');
html = html.replace("default-src 'self' http: https: data: blob:;",
  "default-src 'self' data: blob:; connect-src 'self';");
html = html.replace('<script src="./shared-core.js"></script>',
  '<script src="./prelude.js"></script><script src="./shared-core.js"></script><script src="./helpers.js"></script>');
html = html.replace('<script src="./renderer.js"></script>',
  '<script src="./offline.js"></script><script src="./renderer.js"></script>');
html = html.replace('</head>', '<link rel="stylesheet" href="./mobile.css" /></head>');
await writeFile(join(out,'index.html'), html);
await cp(join(root,'dshgit/LICENSE'), join(out,'LICENSE'));
await writeFile(join(out,'SOURCE.json'), JSON.stringify({
  repository:manifest.repository,commit:manifest.commit,version:'0.4.0',
  android:'Android overlay adapter with optional native DeepSeek API; original pet core and assets preserved.',
  verifiedFiles:checked.length,animationCount:109,
}, null, 2));
console.log('Prepared original pet engine and 109 verified animations for Android.');
