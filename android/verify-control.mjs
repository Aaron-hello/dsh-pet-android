import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';

if(!process.env.PLAYWRIGHT_MODULE) throw new Error('Set PLAYWRIGHT_MODULE to an existing installation');
const {chromium}=await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE));
const root=resolve('app/src/main/assets/control');
const out=resolve('app/build/control-review');
await mkdir(out,{recursive:true});
const server=createServer(async(req,res)=>{
  try{
    const path=decodeURIComponent(new URL(req.url,'http://localhost').pathname);
    const file=path==='/control/pet-font.ttf'
      ?resolve('app/build/generated/petAssets/pet/assets/fonts/上首软糖体.ttf')
      :join(root,path.replace(/^\/control\//,''));
    if(!file.startsWith(root+'/')&&!file.endsWith('/上首软糖体.ttf')) throw new Error('path');
    const data=await readFile(file);
    const type=file.endsWith('.html')?'text/html':file.endsWith('.css')?'text/css':
      file.endsWith('.js')?'text/javascript':file.endsWith('.ttf')?'font/ttf':'image/png';
    res.writeHead(200,{'Content-Type':type,'Content-Length':data.length});res.end(data);
  }catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
let browser;
try{
  browser=await chromium.launch({
    executablePath:process.env.CHROME_EXECUTABLE||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless:true,args:['--renderer-process-limit=1','--disable-gpu'],
  });
  const results=[];
  for(const [width,height] of [[320,760],[360,800],[393,852],[768,1024]]){
    const page=await browser.newPage({viewport:{width,height},deviceScaleFactor:1.5,hasTouch:true});
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.addInitScript(()=>{
      let state={phase:'idle',size:200,count:1,overlayAllowed:false,settingsPending:false};
      window.calls=[];
      window.testState=patch=>{state={...state,...patch};window.PetUI.update(state);};
      window.PetControl={
        ready(){window.PetUI.update(state);window.PetUI.updateApi({ready:true,configured:false});},
        addApiKey(){window.calls.push('nativeBillingNotice');},
        refreshBalance(){window.calls.push('refreshBalance');},
        deleteApiKey(){window.calls.push('deleteApiKey');},
        openPricing(){window.calls.push('openPricing');},
        companionSettings(){window.calls.push('companionSettings');},
        editPersona(){window.calls.push('editPersona');},
        editEmotion(){window.calls.push('editEmotion');},
        manageMemory(){window.calls.push('manageMemory');},
        navigate(page){window.calls.push({navigate:page});},
        openTodos(){window.calls.push('openTodos');},
        start(){window.calls.push('start');window.testState({phase:'starting'});},
        stop(){window.calls.push('stop');window.testState({phase:'idle'});},
        openHome(){window.calls.push('home');},
        requestOverlayPermission(){window.calls.push('permission');},
        saveSettings(size,count){window.calls.push({size,count});window.testState({size,count});},
        openSource(){window.calls.push('source');},
      };
    });
    await page.goto('http://127.0.0.1:'+server.address().port+'/control/index.html');
    await page.evaluate(()=>document.fonts.ready);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'Horizontal overflow');
    assert.equal(await page.locator('.hero-pet').evaluate(el=>el.complete&&el.naturalWidth>0),true);
    const primary=await page.locator('#launch').boundingBox();
    assert.ok(primary.y+primary.height<=height,'Primary action should fit first screen');
    const smallTargets=await page.evaluate(()=>[...document.querySelectorAll('button,input[type="range"]')]
      .filter(el=>el.getClientRects().length)
      .filter(el=>{const r=el.getBoundingClientRect();return r.width<44||r.height<44;})
      .map(el=>el.id||el.textContent));
    assert.deepEqual(smallTargets,[],'Touch controls must be at least 44px');
    const contrasts=await page.evaluate(()=>{
      const luminance=color=>{
        const rgb=color.match(/[\d.]+/g).slice(0,3).map(Number).map(c=>{
          c/=255;return c<=.04045?c/12.92:((c+.055)/1.055)**2.4;
        });return .2126*rgb[0]+.7152*rgb[1]+.0722*rgb[2];
      };
      const ratio=(a,b)=>{const x=luminance(a),y=luminance(b);return (Math.max(x,y)+.05)/(Math.min(x,y)+.05);};
      return [
        ['intro',getComputedStyle(document.querySelector('.intro')).color,'rgb(251,246,237)'],
        ['primary',getComputedStyle(document.querySelector('.primary')).color,getComputedStyle(document.querySelector('.primary')).backgroundColor],
        ['settings',getComputedStyle(document.querySelector('.settings-note')).color,getComputedStyle(document.querySelector('.settings-card')).backgroundColor],
      ].map(([label,foreground,background])=>({label,ratio:ratio(foreground,background)}));
    });
    assert.ok(contrasts.every(item=>item.ratio>=4.5),'Body/button text contrast');
    await page.screenshot({path:join(out,'phone-'+width+'.png'),fullPage:true,animations:'disabled'});
    await page.locator('#launch').click();
    assert.equal(await page.locator('#sheet').isVisible(),true);
    if(width===393)await page.screenshot({path:join(out,'permission-guide.png'),animations:'disabled'});
    await page.locator('#sheet-action').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('permission')));
    await page.evaluate(()=>window.testState({overlayAllowed:true}));
    assert.equal(await page.locator('#sheet').isVisible(),false);
    await page.locator('#launch').click();
    assert.equal(await page.locator('#launch').isDisabled(),true);
    await page.evaluate(()=>window.testState({phase:'running'}));
    await page.locator('#launch').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('home')));
    await page.locator('[data-count="2"]').click();
    assert.equal(await page.locator('[data-count="2"]').getAttribute('aria-pressed'),'true');
    await page.locator('#help').click();
    await page.keyboard.press('Shift+Tab');
    assert.equal(await page.locator('#sheet-action').evaluate(el=>el===document.activeElement),true);
    await page.keyboard.press('Escape');
    await page.locator('#persona-edit').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('editPersona')));
    await page.locator('#emotion-edit').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('editEmotion')));
    await page.locator('[data-nav="settings"]').click();
    assert.equal(await page.locator('.welcome').isVisible(),false);
    assert.equal(await page.locator('#memory-manage').isVisible(),true);
    await page.locator('#memory-manage').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('manageMemory')));
    if(width===393)await page.screenshot({path:join(out,'settings.png'),fullPage:true,animations:'disabled'});
    await page.locator('#api-add').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('nativeBillingNotice')));
    assert.equal(await page.locator('input[type="password"]').count(),0,'The API key is entered natively');
    await page.evaluate(()=>window.PetUI.updateApi({ready:true,configured:true,suffix:'1234',
      balanceTime:Date.now(),balance:{available:true,rows:[{currency:'CNY',total_balance:'12.3456',granted_balance:'2.0000',topped_up_balance:'10.3456'}]}}));
    assert.match(await page.locator('#api-balance').textContent(),/12.3456/);
    await page.locator('#companion-manage').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('companionSettings')));
    await page.locator('#api-refresh').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('refreshBalance')));
    await page.evaluate(()=>window.PetUI.updateApi({ready:true,configured:true,error:'网络暂不可用',
      balance:{rows:[{currency:'CNY',total_balance:'12.3456',granted_balance:'2.0000',topped_up_balance:'10.3456'}]}}));
    assert.match(await page.locator('#api-balance').textContent(),/12.3456/);
    assert.equal(await page.locator('#api-error').isVisible(),true);
    await page.locator('[data-nav="chat"]').click();
    assert.ok(await page.evaluate(()=>window.calls.some(item=>item.navigate==='chat')));
    await page.locator('[data-nav="pet"]').click();
    await page.locator('#todo-open').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('openTodos')));
    await page.emulateMedia({reducedMotion:'reduce'});
    assert.equal(await page.locator('.welcome').evaluate(el=>getComputedStyle(el).animationName),'none');
    await page.evaluate(()=>window.testState({phase:'error',message:'这次没有成功出来',detail:'系统窗口未能创建'}));
    assert.equal(await page.locator('#error-card').isVisible(),true);
    await page.locator('#error-card details').click();
    assert.match(await page.locator('#error-detail').textContent(),/系统窗口/);
    assert.deepEqual(errors,[]);
    results.push({width,height,layout:'passed',permissionFlow:'passed',runningAndErrorStates:'passed',
      keyboardFocus:'passed',reducedMotion:'passed',apiCard:'native billing entry, companion settings and balance/error checked',
      touchTargets:'44px minimum',textContrast:contrasts,runtimeErrors:errors});
    await page.close();
  }
  await writeFile(join(out,'results.json'),JSON.stringify(results,null,2)+'\n');
  console.log(JSON.stringify(results,null,2));
}finally{
  if(browser)await browser.close();
  await new Promise(resolve=>server.close(resolve));
}
