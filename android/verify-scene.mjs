// Optional browser integration check. Supply PLAYWRIGHT_MODULE pointing to an
// existing Playwright installation; this check never downloads a browser.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const modulePath = process.env.PLAYWRIGHT_MODULE;
if (!modulePath) throw new Error('Set PLAYWRIGHT_MODULE to an installed playwright/index.mjs');
const { chromium } = await import(pathToFileURL(modulePath));
const assets = resolve('app/build/generated/petAssets');
const server = createServer(async (req, res) => {
  try {
    const path = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    let file = path.replace(/^\//, '');
    if (path === '/dsh-pet-7340/config') file = 'pet/config.json';
    else if (path.startsWith('/dsh-pet-7340/thumb/main/')) file = 'pet/assets/webm/'+path.split('/').pop();
    else if (path.startsWith('/dsh-pet-7340/font/')) file = 'pet/assets/fonts/'+path.split('/').pop();
    else if (path.startsWith('/dsh-pet-7340/pic/memes/')) file = 'pet/assets/memes/'+path.split('/').pop();
    else if (path.startsWith('/dsh-pet-7340/pic/')) file = 'pet/assets/pic/'+path.split('/').pop();
    else if (path.startsWith('/dsh-pet-7340/sound/')) file = 'pet/assets/sound/'+path.split('/').pop();
    if (!resolve(assets,file).startsWith(assets+'/')) throw new Error('path');
    const data = await readFile(join(assets,file));
    const mime = file.endsWith('.html') ? 'text/html' : file.endsWith('.js') ? 'text/javascript' :
      file.endsWith('.css') ? 'text/css' : file.endsWith('.json') ? 'application/json' :
      file.endsWith('.webm') ? 'video/webm' : file.endsWith('.ttf') ? 'font/ttf' : 'image/png';
    res.writeHead(200, {'Content-Type':mime,'Content-Length':data.length});res.end(data);
  } catch { res.writeHead(503);res.end('offline'); }
});
await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
let browser;
try {
  browser = await chromium.launch({
    executablePath:process.env.CHROME_EXECUTABLE || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless:true,args:['--disable-gpu','--renderer-process-limit=1'],
  });
  const page = await browser.newPage({viewport:{width:500,height:1000},hasTouch:true,deviceScaleFactor:3});
  const errors=[];
  page.on('pageerror',error => errors.push(error.message));
  await page.addInitScript(() => {
    window.nativeBounds=[];
    window.nativeToasts=[];
    window.nativeReady=[];
    window.nativeErrors=[];
    window.chatOpens=0;
    window.whisperRequests=[];
    window.balanceQueries=0;
    window.todoOpens=0;
    window.AndroidPet={
      setBounds(x,y,width,height,boxX,boxY,size,bottomPad,vx,vy) {
        window.nativeBounds.push({x,y,width,height,boxX,boxY,size,bottomPad,vx,vy});
      },
      setInputRegions() {}, ready(id) { window.nativeReady.push(id); }, reportFlight() {}, reportCollide() {},
      error(message) { window.nativeErrors.push(message); },
      openSettings() {},toast(text) { window.nativeToasts.push(text); },
      openChat() { window.chatOpens++; },
      whisper(image) { window.whisperRequests.push(image); },
      showBalance() { window.balanceQueries++; },
      openTodos() { window.todoOpens++; },
    };
  });
  const origin='http://127.0.0.1:'+server.address().port;
  await page.goto(origin+'/pet/index.html?configUrl='+encodeURIComponent(origin+'/dsh-pet-7340/config')+
    '&workAreaW=393&workAreaH=740&petSize=200&instanceIndex=0&openTarget=standalone');
  await page.waitForFunction(() => window.__dshPetDebug?.configOk &&
    window.PetAndroid?.sprite && document.querySelector('video.is-front')?.readyState >= 2, {timeout:15000});
  const canvas = await page.evaluate(() => window.nativeBounds.at(-1));
  await page.setViewportSize({width:Math.ceil(canvas.width),height:Math.ceil(canvas.height)});
  await page.waitForFunction(() => window.nativeReady.length===1);
  await page.locator('.pet-stage').screenshot({
    path:process.env.HERO_OUTPUT || 'app/build/pet-preview.png',omitBackground:true,animations:'disabled',
  });
  assert.equal(await page.evaluate(() => window.PetAndroid.sprite.size),200);
  assert.equal(await page.evaluate(() => window.__dshPetDebug.offline),true);

  await page.evaluate(() => window.PetAndroid.sprite.onMenuAction({action:'sleep'}));
  await page.waitForFunction(() => window.PetAndroid.sprite.sleeping);
  const sleepPhase=await page.evaluate(() => window.PetAndroid.sprite.sleepPhase);
  assert.ok(['start','loop'].includes(sleepPhase));
  await page.evaluate(() => window.PetAndroid.sprite.onMenuAction({action:'wake'}));
  await page.waitForFunction(() => !window.PetAndroid.sprite.sleeping, {timeout:10000});

  const movement=await page.evaluate(async () => {
    const pet=window.PetAndroid.sprite;
    const before={...pet.pos};
    pet.startThrow(before.x,before.y,-600,200);
    await new Promise(resolve => setTimeout(resolve,160));
    const after={...pet.pos};
    pet.stopThrow(); pet.stopMove();
    return {before,after};
  });
  assert.notDeepEqual(movement.before,movement.after);

  await page.evaluate(() => {
    const pet=window.PetAndroid.sprite;
    const rect=pet.hit.getBoundingClientRect();
    pet.hit.dispatchEvent(new PointerEvent('pointerdown',{
      bubbles:true,button:0,pointerType:'touch',pointerId:11,
      clientX:rect.x+rect.width/2,clientY:rect.y+rect.height/2,screenX:180,screenY:400,
    }));
  });
  await page.waitForFunction(() => window.PetAndroid.sprite.menuOpen, {timeout:3000});
  assert.ok(await page.locator('.dsh-pet-menu-column:visible').count() > 0);
  assert.equal(await page.locator('.dsh-pet-menu-hint:visible').count(),0);
  await page.getByText('查看余额',{exact:true}).tap();
  assert.equal(await page.evaluate(()=>window.balanceQueries),1);
  assert.equal(await page.evaluate(()=>window.chatOpens),0,'Balance must not open chat');
  await page.evaluate(() => window.PetAndroid.balanceReply({ready:true,configured:true,balanceBusy:true}));
  assert.match(await page.locator('.pet-bubble').textContent(),/正在查询/);
  await page.evaluate(() => window.PetAndroid.balanceReply({ready:true,configured:true,balanceBusy:false,
    balanceTime:Date.now(),balance:{rows:[{currency:'CNY',total_balance:'12.3456'},{currency:'USD',total_balance:'-0.0100'}]}}));
  assert.match(await page.locator('.pet-bubble').textContent(),/CNY 12.3456/);
  assert.match(await page.locator('.pet-bubble').textContent(),/USD -0.0100/);
  assert.equal(await page.evaluate(()=>window.PetAndroid.sprite.bubbleSlot),'balance');
  await page.locator('.pet-bubble').screenshot({path:'app/build/balance-bubble-review.png'});
  await page.evaluate(() => window.PetAndroid.balanceReply({ready:true,configured:true,error:'网络不可用',
    balance:{rows:[{currency:'CNY',total_balance:'12.3456'}]}}));
  assert.match(await page.locator('.pet-bubble').textContent(),/查询失败/);
  assert.match(await page.locator('.pet-bubble').textContent(),/上次记录/);
  assert.match(await page.locator('.pet-bubble').textContent(),/12.3456/);
  await page.evaluate(() => window.PetAndroid.balanceReply({ready:true,configured:false}));
  assert.match(await page.locator('.pet-bubble').textContent(),/添加 API 密钥/);
  assert.equal(await page.evaluate(()=>window.chatOpens),0);
  await page.evaluate(() => window.PetAndroid.sprite.onContextMenu(new MouseEvent('contextmenu',{
    clientX:80,clientY:80,cancelable:true,bubbles:true,button:2,
  })));
  await page.getByText('对话',{exact:true}).tap();
  assert.equal(await page.evaluate(()=>window.chatOpens),1);
  await page.evaluate(() => window.PetAndroid.sprite.onContextMenu(new MouseEvent('contextmenu',{
    clientX:80,clientY:80,cancelable:true,bubbles:true,button:2,
  })));
  await page.locator('.dsh-pet-menu-label:visible').filter({hasText:/^碎碎念$/}).first().tap();
  assert.equal(await page.evaluate(()=>window.whisperRequests.length),1);
  await page.evaluate(() => window.PetAndroid.sprite.onContextMenu(new MouseEvent('contextmenu',{
    clientX:80,clientY:80,cancelable:true,bubbles:true,button:2,
  })));
  await page.locator('.dsh-pet-menu-label:visible').filter({hasText:/^配图$/}).tap();
  await page.locator('.dsh-pet-menu-label:visible').filter({hasText:/^纳入对话的配图$/}).tap();
  await page.screenshot({path:'app/build/mobile-menu-review.png'});
  const visibleLabels = await page.locator('.dsh-pet-menu-label:visible').allTextContents();
  if (!visibleLabels.includes('可爱')) throw new Error('Picture submenu labels: '+JSON.stringify(visibleLabels));
  await page.locator('.dsh-pet-menu-label:visible').filter({hasText:/^可爱$/}).tap();
  assert.equal(await page.evaluate(()=>window.whisperRequests.at(-1)),'可爱');
  await page.evaluate(() => window.PetAndroid.companionReply({text:'测试陪伴气泡',image:'可爱',dream:false}));
  await page.waitForFunction(() => document.querySelector('.pet-bub-img')?.naturalWidth > 0);
  const action = await page.evaluate(() => {
    const name=window.PetOffline.chatActionNames(window.PetAndroid.sprite.animations)[0];
    window.PetAndroid.apiReply('好呀，主人。',{action:name});return name;
  });
  await page.waitForFunction(name=>window.PetAndroid.sprite.anim===name, action);
  await page.evaluate(() => window.PetAndroid.sprite.onMenuAction({action:'todo'}));
  assert.equal(await page.evaluate(()=>window.todoOpens),1);
  await page.evaluate(() => window.PetAndroid.sprite.onMenuAction({action:'third-party'}));
  assert.ok(await page.evaluate(() => window.nativeToasts.some(text => text.includes('离线'))));
  assert.deepEqual(errors,[]);
  assert.deepEqual(await page.evaluate(()=>window.nativeErrors),[]);
  console.log(JSON.stringify({passed:true,checks:['original video decode','mobile scale','sleep/wake',
    'original throw motion','500ms touch menu','phone menu has no keyboard hints','balance bubble without chat',
    'balance loading, exact currencies, cached error and missing-key states','tap chat/whisper entries',
    'tap cascading picture submenu','original meme asset decode','API reply plays an allowed original action',
    'native local todo entry','remaining offline capability message'],movement,
    runtimeErrors:errors,nativeBoundsUpdates:await page.evaluate(() => window.nativeBounds.length)},null,2));
} finally {
  if(browser) await browser.close();
  await new Promise(resolve => server.close(resolve));
}
