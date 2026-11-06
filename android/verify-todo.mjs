// Fixture-only UI verification; never opens a microphone, alarm or paid API.
import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const {chromium}=await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE));
const root=resolve('app/src/main/assets/todo'),out=resolve('app/build/todo-review');
await mkdir(out,{recursive:true});
const server=createServer(async(req,res)=>{
  try{
    const path=decodeURIComponent(new URL(req.url,'http://localhost').pathname);
    const file=path==='/todo/hero.png'?resolve('app/src/main/assets/control/hero.png'):
      path==='/todo/pet-font.ttf'?resolve('app/build/generated/petAssets/pet/assets/fonts/上首软糖体.ttf'):
      join(root,path.replace(/^\/todo\//,''));
    if(!file.startsWith(root+'/')&&!file.endsWith('/hero.png')&&!file.endsWith('/上首软糖体.ttf'))throw Error();
    const bytes=await readFile(file);res.writeHead(200,{'Content-Type':file.endsWith('.html')?'text/html':
      file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':file.endsWith('.ttf')?'font/ttf':'image/png'});res.end(bytes);
  }catch{res.writeHead(404);res.end();}
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
let browser;
try{
  browser=await chromium.launch({executablePath:process.env.CHROME_EXECUTABLE||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless:true,args:['--disable-gpu','--renderer-process-limit=1']});
  const results=[];
  for(const [width,height] of [[320,740],[393,852],[360,400],[768,1024]]){
    const page=await browser.newPage({viewport:{width,height},hasTouch:true,deviceScaleFactor:1.5});
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.addInitScript(()=>{
      window.calls=[];
      window.fixture={ready:true,items:[{id:'todo-fixture',text:'给家里的花浇水',dueAt:null,done:false}],notifications:false,exact:false,api:true,analyzing:false};
      window.saveCount=0;
      window.TodoNative={
        ready(){window.TodoUI.update(window.fixture);},close(){window.calls.push('close');},
        notifications(){window.calls.push('notificationPermission');},exact(){window.calls.push('exactPermission');},
        batterySettings(){window.calls.push('batterySettings');},systemAlarm(id){window.calls.push({systemAlarm:id});},
        save(json){const value=JSON.parse(json);window.saveCount++;window.calls.push({save:value});
          if(value.id)Object.assign(window.fixture.items.find(x=>x.id===value.id),value);
          else value.items.forEach(item=>window.fixture.items.push({...item,id:'todo-'+window.fixture.items.length,done:false}));
          window.TodoUI.update(window.fixture);window.TodoUI.saved(true,'已保存到手机。');},
        toggle(id,done){window.calls.push({toggle:id,done});window.fixture.items.find(x=>x.id===id).done=done;window.TodoUI.update(window.fixture);},
        remove(id){window.calls.push({remove:id});},connectApi(){window.calls.push('connectApi');},
        voice(){window.calls.push('voice');window.TodoUI.voiceStatus(true,'正在听…');},
        stopVoice(){window.calls.push('voiceStop');window.TodoUI.voiceStatus(false,'');},cancelVoice(){window.calls.push('voiceCancel');},
        analyze(text){window.calls.push({analyze:text});window.TodoUI.analysis([
          {text:'买药',dueAt:Date.now()+86400000,note:'请确认提醒时间。'},
          {text:'<img src=x onerror=window.pwned=1>',dueAt:null,note:'缺少具体时间，请手动选择。'}
        ],'');},
        cancelAnalysis(){window.calls.push('analysisCancel');},
      };
    });
    await page.goto('http://127.0.0.1:'+server.address().port+'/todo/index.html');
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    await page.screenshot({path:join(out,`todo-${width}x${height}.png`),fullPage:true});
    await page.locator('#notifications').click();await page.locator('#exact').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('notificationPermission')&&window.calls.includes('exactPermission')));
    await page.locator('#battery-settings').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('batterySettings')));
    assert.equal(await page.locator('[data-id="todo-fixture"]').getByText('手机时钟').count(),0);
    await page.locator('#add').click();await page.locator('#title').fill('买一束花');
    await page.locator('#remind').check();
    await page.locator('#save').click();
    assert.equal(await page.evaluate(()=>window.saveCount),1);
    assert.equal(await page.locator('#editor').isVisible(),false);
    assert.match(await page.locator('#list').textContent(),/买一束花/);
    assert.ok(await page.evaluate(()=>window.calls.find(x=>x.save)?.save.items[0].dueAt>Date.now()));
    await page.locator('[data-id="todo-1"]').getByText('手机时钟').click();
    assert.ok(await page.evaluate(()=>window.calls.some(x=>x.systemAlarm==='todo-1')));
    await page.locator('.todo').first().locator('.toggle').click();
    await page.locator('[data-filter="done"]').click();
    assert.match(await page.locator('#list').textContent(),/给家里的花浇水/);
    await page.locator('[data-filter="open"]').click();
    await page.locator('#voice-add').click();
    assert.equal(await page.evaluate(()=>window.saveCount),1,'voice start never saves');
    await page.evaluate(()=>window.TodoUI.transcript('明天下午三点买药，再记下整理书桌。',true));
    assert.match(await page.locator('#source').inputValue(),/买药/);
    assert.equal(await page.evaluate(()=>window.calls.filter(x=>x.analyze).length),0,'speech never auto-charges AI');
    await page.locator('#analyze').click();
    assert.equal(await page.locator('.draft').count(),2);
    assert.equal(await page.evaluate(()=>window.saveCount),1,'AI preview never saves');
    assert.equal(await page.evaluate(()=>window.pwned),undefined);
    assert.equal(await page.locator('.draft').nth(1).locator('.draft-remind').isChecked(),false);
    await page.locator('.draft').first().locator('.draft-text').fill('去药店买药');
    await page.locator('#save-drafts').click();
    assert.equal(await page.evaluate(()=>window.saveCount),2);
    assert.match(await page.locator('#list').textContent(),/去药店买药/);
    assert.equal(await page.evaluate(()=>window.pwned),undefined);
    await page.locator('.todo').first().getByText('编辑',{exact:true}).click();
    await page.locator('#title').fill('修改后的事项');await page.locator('#save').click();
    assert.match(await page.locator('#list').textContent(),/修改后的事项/);
    await page.locator('.todo').first().getByText('删除',{exact:true}).click();
    assert.ok(await page.evaluate(()=>window.calls.some(x=>x.remove)));
    await page.locator('#voice-add').click();await page.evaluate(()=>window.TodoUI.voiceStatus(false,'测试：无系统识别服务，可打字。'));
    await page.evaluate(()=>{window.fixture.api=false;window.TodoUI.update(window.fixture);});
    assert.equal(await page.locator('#analyze').isDisabled(),true);
    assert.equal(await page.locator('#connect-api').isVisible(),true);
    await page.locator('#capture-close').click();
    assert.deepEqual(errors,[]);
    results.push({width,height,layout:'passed',manualSaveEditCompleteDelete:'passed',reminderPermissionEntries:'passed',
      voiceRequiresExplicitAiAction:'passed',aiPreviewRequiresExplicitSave:'passed',ambiguousTimeUnscheduled:'passed',
      textOnlyRendering:'passed',noKeyManualFallback:'passed',systemClockAndBatteryEntries:'passed',runtimeErrors:errors});
    await page.close();
  }
  await writeFile(join(out,'results.json'),JSON.stringify(results,null,2)+'\n');console.log(JSON.stringify(results));
}finally{if(browser)await browser.close();await new Promise(r=>server.close(r));}
