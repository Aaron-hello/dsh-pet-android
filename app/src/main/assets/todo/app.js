'use strict';
(() => {
  const $=id=>document.getElementById(id), native=()=>window.TodoNative;
  let state={ready:false,items:[],api:false,analyzing:false},filter='open',editing=null,saving=false,listening=false,voicePrefix='',drafts=[];
  let toastTimer;
  function notice(text){clearTimeout(toastTimer);$('notice').textContent=text;$('notice').hidden=!text;if(text)toastTimer=setTimeout(()=>$('notice').hidden=true,5500);}
  const format=ms=>new Date(ms).toLocaleString('zh-CN',{year:new Date(ms).getFullYear()!==new Date().getFullYear()?'numeric':undefined,month:'short',day:'numeric',hour:'2-digit',minute:'2-digit',hour12:false});
  function localInput(ms){const d=new Date(ms),pad=n=>String(n).padStart(2,'0');return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;}
  function defaultTime(){const value=new Date(Date.now()+3600000);value.setSeconds(0,0);return localInput(value.getTime());}
  function button(label,action,className=''){const el=document.createElement('button');el.textContent=label;el.className=className;el.addEventListener('click',action);return el;}
  function render(controlsOnly=false){
    $('count').textContent=!state.ready?'正在读取…':`${state.items.filter(x=>!x.done).length} 件小事，慢慢来`;
    $('load-error').hidden=!state.error;$('load-error').textContent=state.error||'';
    $('permissions').hidden=state.notifications&&state.exact&&!state.reminderError;
    $('notifications').hidden=!!state.notifications;$('exact').hidden=!!state.exact;
    $('permission-note').textContent=state.reminderError||(!state.notifications?'通知还未开启，待办仍会保存在这里。开启后才会显示到期通知。':
      '当前使用普通提醒，省电状态下可能延迟。开启“准时提醒”可使用系统精确闹钟。');
    $('add').disabled=!state.ready||!!state.error;$('voice-add').disabled=!state.ready||!!state.error;
    $('analyze').disabled=!state.api||state.analyzing||listening||!$('source').value.trim();
    $('analyze').hidden=state.analyzing;$('analysis-stop').hidden=!state.analyzing;
    $('connect-api').hidden=!!state.api;$('source').disabled=listening||state.analyzing;
    $('voice').disabled=state.analyzing||listening;
    $('save-drafts').disabled=saving||state.analyzing||listening;
    if(controlsOnly)return;
    $('list').replaceChildren();
    const items=state.items.filter(x=>filter==='all'||(filter==='done'?x.done:!x.done));
    if(!items.length){const el=document.createElement('div');el.className='empty';const heart=document.createElement('span');heart.textContent='♡';const h=document.createElement('h2');h.textContent=filter==='done'?'完成的小事，会留在这里':'给今天，留一点空白';const p=document.createElement('p');p.textContent=filter==='done'?'每一个小小完成，都算数。':'想起什么，就轻轻记下来。';el.append(heart,h,p);$('list').append(el);}
    for(const item of items){
      const row=document.createElement('article');row.className='todo'+(item.done?' done':'');row.dataset.id=item.id;
      const check=button('',()=>native()?.toggle(item.id,!item.done),'toggle');check.setAttribute('aria-label',item.done?'重新打开待办':'标记完成');check.setAttribute('aria-pressed',String(item.done));
      const tick=document.createElement('span');tick.textContent=item.done?'✓':'';check.append(tick);
      const content=document.createElement('div');content.className='content';const title=document.createElement('p');title.className='title';title.textContent=item.text;
      const due=document.createElement('div');due.className='due'+(!item.done&&item.dueAt&&item.dueAt<Date.now()?' overdue':'');due.textContent=item.done?'已经完成了':item.dueAt?(item.dueAt<Date.now()?'已到期 · ':'提醒 · ')+format(item.dueAt):'暂不设置提醒';
      const actions=document.createElement('div');actions.className='row-actions';actions.append(button('编辑',()=>edit(item)));
      if(!item.done&&item.dueAt&&item.dueAt>Date.now())actions.append(button('手机时钟',()=>native()?.systemAlarm(item.id)));
      actions.append(button('删除',()=>native()?.remove(item.id),'delete'));
      content.append(title,due,actions);row.append(check,content);$('list').append(row);
    }
  }
  function update(value){state=value;render();}
  function edit(item=null){editing=item?.id||null;$('editor-title').textContent=item?'改一件小事':'记一件小事';$('title').value=item?.text||'';$('remind').checked=!!item?.dueAt;$('time-wrap').hidden=!item?.dueAt;$('time').value=item?.dueAt?localInput(item.dueAt):defaultTime();$('editor').hidden=false;setTimeout(()=>$('title').focus(),0);}
  function dueValue(enabled,input){if(!enabled)return null;const value=new Date(input.value).getTime();if(!Number.isFinite(value))throw Error('请先选择有效的提醒日期和时间。');return value;}
  function save(payload){saving=true;$('save').disabled=true;$('save-drafts').disabled=true;native()?.save(JSON.stringify(payload));}
  function saved(ok,message){saving=false;$('save').disabled=false;if(ok){$('editor').hidden=true;$('capture').hidden=true;drafts=[];}render();notice(message);}
  function openCapture(){drafts=[];$('drafts').replaceChildren();$('draft-area').hidden=true;$('source').value='';$('voice-status').textContent='';$('capture').hidden=false;render();}
  function closeCapture(){native()?.cancelVoice();if(state.analyzing)native()?.cancelAnalysis();$('capture').hidden=true;}
  function analysis(rows,error){
    if(!rows){notice(error||'没有收到草稿，请手动输入或重试。');return;}
    if($('capture').hidden)return;
    drafts=rows;$('drafts').replaceChildren();
    rows.forEach((draft,i)=>{
      const card=document.createElement('section');card.className='draft';card.dataset.index=i;
      const selectLabel=document.createElement('label');selectLabel.className='check-label';const selected=document.createElement('input');selected.type='checkbox';selected.checked=true;selected.className='selected-draft';selectLabel.append(selected,document.createTextNode(`记录第 ${i+1} 件事`));
      const text=document.createElement('textarea');text.value=draft.text;text.maxLength=200;text.rows=2;text.className='draft-text';text.setAttribute('aria-label',`第 ${i+1} 项内容`);
      const remindLabel=document.createElement('label');remindLabel.className='check-label';const remind=document.createElement('input');remind.type='checkbox';remind.className='draft-remind';remind.checked=!!draft.dueAt;remindLabel.append(remind,document.createTextNode('到时间提醒我'));
      const time=document.createElement('input');time.type='datetime-local';time.step='1';time.className='draft-time';time.setAttribute('aria-label',`第 ${i+1} 项提醒时间`);time.value=draft.dueAt?localInput(draft.dueAt):defaultTime();time.hidden=!draft.dueAt;remind.addEventListener('change',()=>time.hidden=!remind.checked);
      const note=document.createElement('p');note.className='hint';note.textContent=draft.note||'请确认内容和提醒时间。';card.append(selectLabel,text,remindLabel,time,note);$('drafts').append(card);
    });
    $('draft-area').hidden=false;setTimeout(()=>$('draft-area').scrollIntoView({block:'start'}),0);render();
  }
  function voiceStatus(active,message){listening=active;$('voice').hidden=active;$('voice-stop').hidden=!active;$('voice-status').textContent=message||'';render(true);}
  function transcript(text,final){$('source').value=(voicePrefix+(voicePrefix?'\n':'')+text).slice(0,4000);if(final)voiceStatus(false,'识别好了，可以修改后再点 AI 整理。');else render(true);}
  function voice(){voicePrefix=$('source').value.trim();native()?.voice();}
  $('add').addEventListener('click',()=>edit());$('back').addEventListener('click',()=>native()?.close());
  $('editor-close').addEventListener('click',()=>$('editor').hidden=true);$('capture-close').addEventListener('click',closeCapture);
  $('remind').addEventListener('change',()=>{$('time-wrap').hidden=!$('remind').checked;if(!$('time').value)$('time').value=defaultTime();});
  $('edit-form').addEventListener('submit',event=>{event.preventDefault();if(saving)return;try{const text=$('title').value.trim();if(!text)throw Error('先写下要做的事吧。');const dueAt=dueValue($('remind').checked,$('time'));save(editing?{id:editing,text,dueAt}:{items:[{text,dueAt}]});}catch(e){notice(e.message);}});
  $('voice-add').addEventListener('click',()=>{openCapture();voice();});$('voice').addEventListener('click',voice);$('voice-stop').addEventListener('click',()=>native()?.stopVoice());
  $('source').addEventListener('input',()=>render(true));$('analyze').addEventListener('click',()=>{if(!state.api||state.analyzing||!$('source').value.trim())return;$('draft-area').hidden=true;native()?.analyze($('source').value.trim());});
  $('analysis-stop').addEventListener('click',()=>native()?.cancelAnalysis());
  $('save-drafts').addEventListener('click',()=>{if(saving)return;try{const items=[...$('drafts').querySelectorAll('.draft')].filter(card=>card.querySelector('.selected-draft').checked).map(card=>{const text=card.querySelector('.draft-text').value.trim();if(!text)throw Error('选中的事项需要填写内容。');return{text,dueAt:dueValue(card.querySelector('.draft-remind').checked,card.querySelector('.draft-time'))};});if(!items.length)throw Error('先选中至少一件事吧。');save({items});}catch(e){notice(e.message);}});
  $('notifications').addEventListener('click',()=>native()?.notifications());$('exact').addEventListener('click',()=>native()?.exact());$('connect-api').addEventListener('click',()=>native()?.connectApi());
  $('battery-settings').addEventListener('click',()=>native()?.batterySettings());
  document.querySelectorAll('[data-filter]').forEach(el=>el.addEventListener('click',()=>{filter=el.dataset.filter;document.querySelectorAll('[data-filter]').forEach(b=>{b.classList.toggle('selected',b===el);b.setAttribute('aria-pressed',String(b===el));});render();}));
  function back(){if(!$('editor').hidden){$('editor').hidden=true;return true;}if(!$('capture').hidden){closeCapture();return true;}return false;}
  document.addEventListener('keydown',e=>{if(e.key==='Escape')back();});
  document.querySelectorAll('[data-nav]').forEach(b=>{b.classList.toggle('selected',b.dataset.nav==='todo');b.addEventListener('click',()=>native()?.navigate(b.dataset.nav));});
  window.TodoUI={update,notice,saved,analysis,voiceStatus,transcript,back};render();native()?.ready();
})();
