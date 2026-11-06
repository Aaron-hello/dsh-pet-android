import {createServer} from 'node:http';
import {readFile,mkdir,writeFile} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const {chromium}=await import(pathToFileURL(process.env.PLAYWRIGHT_MODULE));
const root=resolve('app/src/main/assets/chat'),out=resolve('app/build/chat-review');
await mkdir(out,{recursive:true});
const server=createServer(async(req,res)=>{
  try{
    const path=decodeURIComponent(new URL(req.url,'http://localhost').pathname);
    const file=path.startsWith('/chat/memes/')?resolve('app/build/generated/petAssets/pet/assets/memes',path.split('/').pop()):
      path==='/chat/avatar.png'?resolve('app/src/main/assets/control/hero.png'):
      path==='/chat/pet-font.ttf'?resolve('app/build/generated/petAssets/pet/assets/fonts/上首软糖体.ttf'):
      join(root,path.replace(/^\/chat\//,''));
    if(!file.startsWith(root+'/')&&!file.includes('/pet/assets/memes/')&&!file.endsWith('/hero.png')&&!file.endsWith('/上首软糖体.ttf'))throw new Error();
    const data=await readFile(file);
    res.writeHead(200,{'Content-Type':file.endsWith('.html')?'text/html':file.endsWith('.js')?'text/javascript':
      file.endsWith('.css')?'text/css':file.endsWith('.ttf')?'font/ttf':'image/png'});res.end(data);
  }catch{res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
let browser;
try{
  browser=await chromium.launch({executablePath:process.env.CHROME_EXECUTABLE||
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless:true,
    args:['--disable-gpu','--renderer-process-limit=1']});
  const results=[];
  for(const [width,height] of [[296,420],[370,460],[370,300]]){
    const page=await browser.newPage({viewport:{width,height},deviceScaleFactor:2,hasTouch:true});
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.addInitScript(()=>{
      window.calls=[];
      window.fixtureAccount={ready:true,configured:true,activePet:null,balanceTime:Date.now(),
        balance:{available:true,rows:[{currency:'CNY',total_balance:'12.3456'}]}};
      window.fixtureHistory={type:'history',pet:'android-0',busy:false,ready:true,messages:[]};
      window.PetChatNative={
        ready(){window.PetChat.account(window.fixtureAccount);window.PetChat.event(window.fixtureHistory);},
        send(text){window.calls.push({send:text});},retry(){window.calls.push('retry');},
        stop(){window.calls.push('stop');},clear(){window.calls.push('clear');},
        refreshBalance(){window.calls.push('refresh');},close(){window.calls.push('close');},
        focusInput(){window.calls.push('focus');},blurInput(){window.calls.push('blur');},
        move(dx,dy){window.calls.push({move:[dx,dy]});},connect(){window.calls.push('connect');},
      };
    });
    await page.goto('http://127.0.0.1:'+server.address().port+'/chat/index.html');
    await page.evaluate(()=>window.PetChat.focusComposer());
    await page.waitForFunction(()=>document.activeElement===document.getElementById('message'));
    assert.ok(await page.evaluate(()=>window.calls.includes('focus')));
    await page.evaluate(()=>document.fonts.ready);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    assert.match(await page.locator('.billing').textContent(),/会计费/);
    await page.locator('#message').fill('今天有点开心');
    await page.locator('#message').dispatchEvent('keydown',{key:'Enter',isComposing:true});
    assert.equal(await page.evaluate(()=>window.calls.filter(x=>x.send).length),0,'IME composition must not send');
    await page.locator('#send').click();
    assert.equal(await page.evaluate(()=>window.calls.find(x=>x.send)?.send),'今天有点开心');
    await page.evaluate(()=>{
      window.fixtureHistory={...window.fixtureHistory,busy:true,messages:[
        {id:'u1',role:'user',content:'今天有点开心',status:'complete'},
        {id:'a1',role:'assistant',content:'',status:'streaming'}]};
      window.PetChat.event(window.fixtureHistory);
      window.PetChat.event({type:'delta',id:'a1',content:'那我也跟着开心啦，主人。'});
    });
    assert.match(await page.locator('[data-message-id="a1"] .bubble').textContent(),/主人/);
    assert.equal(await page.locator('#stop').isVisible(),true);
    await page.locator('#stop').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('stop')));
    await page.evaluate(()=>{
      window.fixtureHistory.busy=false;
      window.fixtureHistory.messages[1]={id:'a1',role:'assistant',content:'那我也跟着开心啦，主人。',status:'complete'};
      window.PetChat.event(window.fixtureHistory);
    });
    await page.screenshot({path:join(out,'chat-'+width+'x'+height+'.png'),animations:'disabled'});
    const bounds=await page.locator('#composer').boundingBox();
    assert.ok(bounds.y+bounds.height<=height,'Composer remains visible in compact keyboard layout');
    await page.evaluate(()=>{
      window.PetChat.event({type:'history',pet:'android-0',ready:true,busy:false,messages:[
        {id:'u1',role:'user',content:'test',status:'complete'},
        {id:'a1',role:'assistant',content:'<img src=x onerror="window.injected=true">',status:'error',error:'网络中断'}]});
    });
    assert.equal(await page.locator('.bubble img').count(),0,'Model output is rendered as text');
    assert.equal(await page.evaluate(()=>window.injected===true),false);
    await page.locator('.retry').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('retry')));
    await page.locator('#clear').click();
    assert.equal(await page.locator('#confirm').isVisible(),true);
    await page.locator('#keep').click();
    assert.equal(await page.evaluate(()=>window.calls.includes('clear')),false);
    await page.locator('#clear').click();await page.locator('#confirm-clear').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('clear')));
    await page.evaluate(()=>window.PetChat.event({type:'history',pet:'android-0',ready:true,busy:false,messages:[]}));
    assert.equal(await page.locator('.message-row').count(),0);
    await page.evaluate(()=>window.PetChat.account({ready:true,configured:false}));
    assert.equal(await page.locator('#message').isDisabled(),true);
    await page.locator('#connect').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('connect')));
    await page.locator('#close').click();
    assert.ok(await page.evaluate(()=>window.calls.includes('close')));
    assert.deepEqual(errors,[]);
    results.push({width,height,streaming:'passed',sendStopRetry:'passed',clearConfirmation:'passed',
      textOnlyRendering:'passed',missingKeyGate:'passed',autoFocus:'passed (DOM focus and native keyboard bridge)',composerVisible:true,runtimeErrors:errors});
    await page.close();
  }
  for(const [width,height] of [[320,740],[393,852],[768,1024]]){
    const page=await browser.newPage({viewport:{width,height},hasTouch:true});
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.addInitScript(()=>{
      window.calls=[];
      window.PetChatNative={ready(){
        window.PetChat.account({ready:true,configured:true,profile:{name:'小蓝'},stickers:[{name:'测试',desc:'开心'}]});
        window.PetChat.event({type:'history',pet:'android-0',ready:true,busy:false,messages:[]});
      },sendSticker(name){window.calls.push({sticker:name});},navigate(page){window.calls.push({page});},focusInput(){},blurInput(){}};
    });
    await page.goto('http://127.0.0.1:'+server.address().port+'/chat/index.html?surface=app');
    assert.equal(await page.locator('.identity h1').textContent(),'小蓝');
    assert.equal(await page.locator('.bottom-nav').isVisible(),true);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    await page.locator('#stickers-open').click();
    assert.match(await page.locator('#stickers').textContent(),/计费/);
    await page.locator('#sticker-list button').click();
    assert.equal(await page.evaluate(()=>window.calls.find(x=>x.sticker)?.sticker),'测试');
    await page.locator('[data-nav="todo"]').click();
    assert.equal(await page.evaluate(()=>window.calls.find(x=>x.page)?.page),'todo');
    await page.evaluate(()=>{
      window.longHistory={type:'history',pet:'android-0',ready:true,busy:false,messages:
        Array.from({length:25},(_,i)=>({id:'long-'+i,role:i%2?'assistant':'user',status:'complete',content:'测试聊天内容。'.repeat(18)}))};
      window.PetChat.event(window.longHistory);
    });
    const atBottom=()=>page.evaluate(()=>{const m=document.getElementById('messages');return m.scrollHeight-m.scrollTop-m.clientHeight<2;});
    await page.waitForFunction(()=>{const m=document.getElementById('messages');return m.scrollHeight-m.scrollTop-m.clientHeight<2;});
    await page.locator('#message').focus();
    await page.setViewportSize({width,height:height-300});
    await page.waitForFunction(()=>{const m=document.getElementById('messages');return m.scrollHeight-m.scrollTop-m.clientHeight<2;});
    assert.equal(await page.locator('.bottom-nav').isVisible(),true,'Navigation remains available with keyboard');
    await page.locator('#message').fill('第一行\n第二行\n第三行\n第四行');
    await page.waitForFunction(()=>{const m=document.getElementById('messages');return m.scrollHeight-m.scrollTop-m.clientHeight<2;});
    await page.locator('#messages').hover();await page.mouse.wheel(0,-700);
    await page.waitForFunction(()=>{const m=document.getElementById('messages');return m.scrollHeight-m.scrollTop-m.clientHeight>100;});
    const before=await page.locator('#messages').evaluate(el=>el.scrollTop);
    await page.evaluate(()=>window.PetChat.event(window.longHistory));
    await page.evaluate(()=>window.PetChat.event({type:'delta',id:'long-24',content:'回复更新'.repeat(300)}));
    await page.waitForTimeout(100);
    const after=await page.locator('#messages').evaluate(el=>el.scrollTop);
    assert.ok(Math.abs(after-before)<3,'History redraw and new reply preserve user position');
    await page.locator('#messages').hover();await page.mouse.wheel(0,100000);
    await page.waitForFunction(()=>{const m=document.getElementById('messages');return m.scrollHeight-m.scrollTop-m.clientHeight<2;});
    await page.evaluate(()=>window.PetChat.event({type:'delta',id:'long-24',content:'回复继续'.repeat(600)}));
    await page.waitForFunction(()=>{const m=document.getElementById('messages');return m.scrollHeight-m.scrollTop-m.clientHeight<2;});
    assert.equal(await atBottom(),true,'Streaming follows the end after user returns to bottom');
    if(width===393)await page.screenshot({path:join(out,'app-chat.png'),animations:'disabled'});
    assert.deepEqual(errors,[]);results.push({width,height,appNavigation:'passed',stickerSelection:'passed',keyboardResizeFollow:'passed',historyPosition:'passed',streamingFollow:'passed',runtimeErrors:errors});
    await page.close();
  }
  await writeFile(join(out,'results.json'),JSON.stringify(results,null,2)+'\n');
  console.log(JSON.stringify(results,null,2));
}finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
