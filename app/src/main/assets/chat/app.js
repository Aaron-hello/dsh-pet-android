'use strict';
(() => {
  const $=id=>document.getElementById(id);
  const appMode=new URLSearchParams(location.search).get('surface')==='app';
  document.body.classList.toggle('app-mode',appMode);
  document.querySelectorAll('[data-nav]').forEach(b=>{b.classList.toggle('selected',b.dataset.nav==='chat');b.addEventListener('click',()=>window.PetChatNative?.navigate(b.dataset.nav));});
  let account={ready:false,configured:false}, busy=false, historyReady=false, historyError='';
  const rows=new Map();
  let drag=null;
  let focusPending=false;
  const messages=$('messages');
  let following=true, userScrollUntil=0, scrollFrame=0, restoring=false;
  const nearBottom=()=>messages.scrollHeight-messages.scrollTop-messages.clientHeight<70;
  function followBottom(){
    if(!following||scrollFrame)return;
    scrollFrame=requestAnimationFrame(()=>{
      scrollFrame=0;
      if(following)messages.scrollTop=messages.scrollHeight;
    });
  }
  function userScroll(){userScrollUntil=Date.now()+1500;restoring=false;if(scrollFrame){cancelAnimationFrame(scrollFrame);scrollFrame=0;}}
  for(const name of ['touchmove','wheel'])messages.addEventListener(name,userScroll,{passive:true});
  messages.addEventListener('pointermove',e=>{if(e.buttons)userScroll();},{passive:true});
  messages.addEventListener('keydown',e=>{if(['ArrowUp','ArrowDown','PageUp','PageDown','Home','End',' '].includes(e.key))userScroll();});
  messages.addEventListener('scroll',()=>{
    if(!restoring&&Date.now()<userScrollUntil)following=nearBottom();
  },{passive:true});
  if(window.ResizeObserver)new ResizeObserver(followBottom).observe(messages);
  window.addEventListener('resize',followBottom);
  window.visualViewport?.addEventListener('resize',followBottom);
  const native=()=>window.PetChatNative;
  function notice(message){$('notice').textContent=message;$('notice').hidden=!message;}
  function controls(){
    const another=account.activePet && account.activePet!==window.currentPet;
    const blocked=!historyReady||!account.configured||account.connecting||!!historyError||!!another;
    $('message').disabled=blocked;
    $('send').disabled=blocked||!$('message').value.trim()||busy;
    $('send').hidden=busy;$('stop').hidden=!busy;
    $('clear').disabled=!historyReady;
    $('refresh').disabled=!account.configured||account.balanceBusy||account.connecting;
    $('refresh').textContent=account.balanceBusy?'更新中':'刷新';
    $('connection').hidden=!!account.configured;
    $('message').placeholder=another?'另一只小家伙正在聊天…':!account.configured?'先连接 DeepSeek，就能聊天啦':'和我说说话吧…';
    if(focusPending&&!blocked&&!busy){
      focusPending=false;
      setTimeout(()=>{if(!$('message').disabled){$('message').focus();native()?.focusInput();}},0);
    }
  }
  function renderAccount(value){
    account=value;
    if(value.profile?.name)document.querySelector('.identity h1').textContent=value.profile.name;
    const list=$('sticker-list');
    if(!list.childElementCount && Array.isArray(value.stickers))value.stickers.forEach(sticker=>{
      const b=document.createElement('button');b.type='button';b.title=sticker.name;b.setAttribute('aria-label','发送表情 '+sticker.name);
      const img=document.createElement('img');img.alt='';img.src='./memes/'+encodeURIComponent(sticker.name)+'.png';b.append(img);
      b.addEventListener('click',()=>{if(!account.configured||busy)return;following=true;native()?.sendSticker(sticker.name);$('stickers').hidden=true;});list.append(b);
    });
    const balance=value.balance;
    $('balance').textContent=!value.configured?'尚未连接':balance?.rows?.length
      ?balance.rows.map(row=>row.currency+' '+row.total_balance).join(' / ')
      :value.balanceBusy?'查询中…':'点刷新查看';
    $('account-error').hidden=!value.error;
    $('account-error').textContent=value.error
      ?(value.balanceTime?'更新失败，上次余额仍保留。':'')+value.error:'';
    $('balance').title=value.balanceTime?'更新于 '+new Date(value.balanceTime).toLocaleTimeString():'';
    controls();
  }
  function add(record){
    const row=document.createElement('div');row.className='message-row '+record.role;
    row.dataset.messageId=record.id;
    const bubble=document.createElement('div');bubble.className='bubble';bubble.textContent=record.content||'';
    const meta=document.createElement('div');meta.className='message-meta';
    row.append(bubble,meta);
    if(record.role==='assistant'){
      const retry=document.createElement('button');retry.className='retry';retry.textContent='重试（会计费）';
      retry.hidden=true;retry.addEventListener('click',()=>native()?.retry());row.append(retry);
    }
    $('messages').append(row);rows.set(record.id,row);updateRow(record);
  }
  function updateRow(record){
    const row=rows.get(record.id);if(!row)return;
    row.querySelector('.bubble').textContent=record.content||'';
    row.classList.toggle('streaming',record.status==='streaming');
    row.querySelector('.message-meta').textContent=record.error||record.note||'';
    const retry=row.querySelector('.retry');
    if(retry)retry.hidden=!['error','stopped','interrupted'].includes(record.status)||busy;
    const existing=row.querySelector('.meme');if(existing)existing.remove();
    if(record.image){
      const image=document.createElement('img');image.className='meme';image.alt=record.image;
      image.addEventListener('load',followBottom);
      image.src='./memes/'+encodeURIComponent(record.image)+'.png';row.prepend(image);
    }
  }
  function event(value){
    if(value.type==='history'){
      const oldTop=messages.scrollTop, shouldFollow=following;
      restoring=true;
      window.currentPet=value.pet;busy=value.busy;historyReady=value.ready;historyError=value.error||'';
      $('messages').replaceChildren();rows.clear();
      if(!value.messages?.length){
        const empty=document.createElement('div');empty.className='empty';
        const heart=document.createElement('span');heart.textContent='♡';
        const title=document.createElement('h2');title.textContent='今天想和我说什么？';
        const text=document.createElement('p');text.textContent='聊聊日常，或者分享一点小心情。';
        empty.append(heart,title,text);$('messages').append(empty);
      }else{
        value.messages.forEach(add);
        const last=value.messages.at(-1);
        rows.forEach((row,id)=>{if(id!==last.id){const retry=row.querySelector('.retry');if(retry)retry.hidden=true;}});
      }
      notice(historyError);
      if(shouldFollow)followBottom();else messages.scrollTop=oldTop;
      requestAnimationFrame(()=>{restoring=false;});controls();
    }else if(value.type==='delta'){
      const row=rows.get(value.id);if(row)row.querySelector('.bubble').textContent=value.content;
      followBottom();
    }else if(value.type==='rejected'){
      if(value.draft&&!$('message').value)$('message').value=value.draft;
      notice(value.message);controls();
    }else if(value.type==='notice')notice(value.message);
  }
  $('composer').addEventListener('submit',e=>{
    e.preventDefault();const text=$('message').value.trim();
    if(!text||$('send').disabled)return;
    following=true;native()?.send(text);$('message').value='';$('message').style.height='';
    $('message').blur();native()?.blurInput();notice('');controls();
  });
  $('message').addEventListener('input',()=>{
    $('message').style.height='auto';$('message').style.height=Math.min(85,$('message').scrollHeight)+'px';followBottom();controls();
  });
  $('message').addEventListener('focus',()=>native()?.focusInput());
  $('message').addEventListener('pointerdown',()=>{if(!$('message').disabled)native()?.focusInput();});
  $('message').addEventListener('blur',()=>native()?.blurInput());
  $('message').addEventListener('keydown',e=>{
    if(e.key==='Enter'&&!e.shiftKey&&!e.isComposing){e.preventDefault();$('composer').requestSubmit();}
  });
  $('stickers-open').addEventListener('click',()=>{$('stickers').hidden=!$('stickers').hidden;followBottom();});
  $('stop').addEventListener('click',()=>native()?.stop());
  $('refresh').addEventListener('click',()=>native()?.refreshBalance());
  $('close').addEventListener('click',()=>native()?.close());
  $('connect').addEventListener('click',()=>native()?.connect());
  $('clear').addEventListener('click',()=>{
    $('message').blur();native()?.blurInput();$('confirm').hidden=false;$('keep').focus();
  });
  $('keep').addEventListener('click',()=>{$('confirm').hidden=true;$('clear').focus();});
  $('confirm-clear').addEventListener('click',()=>{
    following=true;$('confirm').hidden=true;native()?.clear();$('clear').focus();
  });
  const header=$('drag-handle');
  header.addEventListener('pointerdown',e=>{
    if(appMode)return;
    $('message').blur();native()?.blurInput();drag={x:e.screenX,y:e.screenY};
    try{header.setPointerCapture(e.pointerId);}catch{}
  });
  header.addEventListener('pointermove',e=>{
    if(!drag)return;
    native()?.move(e.screenX-drag.x,e.screenY-drag.y);drag={x:e.screenX,y:e.screenY};
  });
  for(const name of ['pointerup','pointercancel','lostpointercapture'])header.addEventListener(name,()=>{drag=null;});
  document.addEventListener('keydown',e=>{
    if(e.key==='Escape'){
      if(!$('confirm').hidden)$('confirm').hidden=true;else native()?.close();
    }
  });
  function focusComposer(){focusPending=true;controls();}
  $('messages').addEventListener('click',e=>{
    if(!e.target.closest('.message-row')&&!window.getSelection()?.toString())focusComposer();
  });
  window.PetChat={account:renderAccount,event,focusComposer};
  controls();native()?.ready();
})();
