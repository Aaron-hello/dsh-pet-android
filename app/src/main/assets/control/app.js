'use strict';
(() => {
  const $ = id => document.getElementById(id);
  let current = {phase:'idle',size:200,count:1,overlayAllowed:false,settingsPending:false};
  let sheetAction = null, previousFocus = null, toastTimer;
  const native = () => window.PetControl;
  const showToast = text => {
    $('toast').textContent = text; $('toast').classList.add('visible');
    clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').classList.remove('visible'),2400);
  };
  function updateSize(value) {
    $('pet-size').value = value;
    document.documentElement.style.setProperty('--range',((value-120)/180*100)+'%');
    $('size-value').textContent = value < 170 ? '迷你一只' : value > 240 ? '软乎乎的大只' : '刚刚好';
  }
  function closeSheet() {
    $('sheet').hidden = true; document.body.style.overflow = '';
    previousFocus?.focus(); sheetAction=null;
  }
  function sheet(title, html, label, action) {
    previousFocus=document.activeElement;
    $('sheet-title').textContent=title; $('sheet-content').innerHTML=html;
    $('sheet-action').querySelector('span').textContent=label;
    sheetAction=action; $('sheet').hidden=false; document.body.style.overflow='hidden';
    document.querySelector('.sheet-close').focus();
  }
  function showPermission() {
    sheet('先给它一张通行证',
      '<p>允许“显示在其他应用上层”，小家伙才能走出这里，出现在手机桌面、微信和其他应用上。</p><ol><li>在系统页面里找到 dsh-pet。</li><li>打开“允许显示在其他应用上层”。</li><li>返回这里，它就会准备出门。</li></ol>',
      '去允许悬浮窗', () => native()?.requestOverlayPermission());
  }
  const help = () => sheet('让小家伙，陪你逛逛',
    '<ol><li>点“带它去桌面”，按提示允许悬浮窗。</li><li>画面加载好后，会自动回到手机桌面。</li><li>打开微信等其他应用，宠物会继续悬浮。</li></ol><p>拖动可以搬家，长按打开动作菜单，甩一下会弹起来。想休息时，回到这里点“让它回家休息”，或使用通知栏里的停止按钮。</p>',
    '知道啦',closeSheet);
  function update(value) {
    const previousPhase = current.phase;
    current={...current,...value};
    const running=current.phase==='running', starting=current.phase==='starting', error=current.phase==='error';
    $('status').className='status '+(running?'running':starting?'starting':error?'error':'');
    $('status-label').textContent=running?'陪伴中':starting?'准备出门':'在家休息';
    if(error) $('status-label').textContent='再试一下';
    $('launch-label').textContent=starting?'小家伙正在出门…':running
      ?(current.settingsPending?'带着新设置去桌面':'去桌面找它'):'带它去桌面';
    $('launch').disabled=starting;
    $('launch').classList.toggle('busy',starting);
    $('recall').hidden=!(running||starting);
    const caption = running ? '离开 App 也会陪着你 · 可随时拖动' : starting
      ? '动画准备好后，就带你回到手机桌面' : current.overlayAllowed
      ? '通行证已准备好，轻点就能相伴' : '首次需要允许悬浮窗，之后轻点就能相伴';
    $('launch-caption').lastChild.textContent=' '+caption;
    $('error-card').hidden=!error;
    if(error) {
      $('error-title').textContent=current.message||'这次没有成功出来';
      $('error-message').textContent='可以重新试一次；如果仍没出来，展开下面的原因查看提示。';
      $('error-detail').textContent=current.detail||'请检查悬浮窗权限后重试。';
      if(previousPhase!=='error') requestAnimationFrame(()=>$('error-card').scrollIntoView({
        block:'nearest',behavior:matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth',
      }));
    }
    updateSize(Number(current.size));
    document.querySelectorAll('[data-count]').forEach(button=>{
      const selected=Number(button.dataset.count)===Number(current.count);
      button.classList.toggle('selected',selected);button.setAttribute('aria-pressed',String(selected));
    });
    $('settings-note').classList.toggle('pending',!!current.settingsPending);
    $('settings-note').textContent=current.settingsPending?'偏爱已记住，点上方按钮让新设置生效。':'放心，你的小偏爱都会被记住。';
    if(current.overlayAllowed && !$('sheet').hidden && $('sheet-title').textContent==='先给它一张通行证') closeSheet();
  }
  function updateApi(value) {
    const connected=!!value.configured,loading=!value.ready;
    $('api-status').textContent=loading?'准备中':value.connecting?'验证中':connected?'已连接':'未连接';
    $('api-status').classList.toggle('connected',connected);
    $('api-add').textContent=connected?'更换密钥':'添加密钥';
    $('api-add').disabled=loading||value.connecting;
    $('api-refresh').hidden=!connected;$('api-remove').hidden=!connected;
    $('api-refresh').disabled=value.balanceBusy||value.connecting;
    $('api-refresh').textContent=value.balanceBusy?'更新中…':'刷新余额';
    $('api-remove').disabled=value.connecting;
    $('api-balance').replaceChildren();
    const balances=value.balance?.rows||[];
    $('api-balance').hidden=!connected||!balances.length;
    for(const row of balances){
      const item=document.createElement('div');item.className='api-balance-row';
      const title=document.createElement('span');title.textContent=row.currency==='CNY'?'人民币余额':'美元余额';
      const amount=document.createElement('strong');amount.textContent=row.total_balance;
      const parts=document.createElement('small');parts.textContent='赠送 '+row.granted_balance+' · 充值 '+row.topped_up_balance;
      item.append(title,amount,parts);$('api-balance').append(item);
    }
    $('api-update').textContent=connected
      ?(value.suffix?'密钥 ···· '+value.suffix+' · ':'')+(value.balanceTime
        ?'更新于 '+new Date(value.balanceTime).toLocaleTimeString():'点“刷新余额”查看最新额度')
      :'不接入也可以继续离线玩耍。';
    $('api-error').hidden=!value.error;
    $('api-error').textContent=value.error||'';
    $('companion-settings').hidden=!connected;
    const companion=value.companion||{};
    const enabled=[companion.whisper?'碎碎念':'',companion.dream?'梦话':'',companion.decorations?'聊天配图与动作':''].filter(Boolean);
    $('companion-status').textContent=companion.paused?'自动调用已暂停：'+companion.paused:
      (enabled.length?enabled.join(' · '):'自动陪伴未开启')+(companion.used?' · 最近一小时自动 '+companion.used+'/'+companion.limit+' 次':'');
  }
  $('launch').addEventListener('click',()=>{
    if(current.phase==='running' && !current.settingsPending) native()?.openHome();
    else if(!current.overlayAllowed) showPermission();
    else native()?.start();
  });
  $('recall').addEventListener('click',()=>{native()?.stop();showToast('休息一下，下次再一起玩。');});
  $('pet-size').addEventListener('input',event=>updateSize(Number(event.target.value)));
  $('pet-size').addEventListener('change',event=>{
    current.size=Number(event.target.value);native()?.saveSettings(current.size,current.count);
  });
  document.querySelectorAll('[data-count]').forEach(button=>button.addEventListener('click',()=>{
    current.count=Number(button.dataset.count);native()?.saveSettings(current.size,current.count);
    update(current);
  }));
  $('help').addEventListener('click',help);
  $('about').addEventListener('click',()=>sheet('小小陪伴，来自热爱',
    '<p>基于 PC2005-cloud 的 dsh-pet 制作，保留 109 段原版动画。</p><p>不接入时可离线玩耍；接入自己的 DeepSeek 密钥后可查询余额和应用内聊天，API 使用会计费。记录保存在本机，最近一段对话会随请求发给 DeepSeek。</p><p>代码遵循 MIT，原素材与形象限非商业使用。感谢原作者带来的可爱。</p>',
    '查看原作者',()=>native()?.openSource()));
  $('sheet-action').addEventListener('click',()=>sheetAction?.());
  document.querySelector('.sheet-close').addEventListener('click',closeSheet);
  $('sheet').addEventListener('click',event=>{if(event.target===$('sheet'))closeSheet();});
  document.addEventListener('keydown',event=>{
    if(event.key==='Escape'&&!$('sheet').hidden){event.preventDefault();closeSheet();}
    if(event.key==='Tab'&&!$('sheet').hidden){
      const elements=[...$('sheet').querySelectorAll('button,a,[tabindex="0"]')];
      const first=elements[0],last=elements.at(-1);
      if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}
      else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}
    }
  });
  $('api-add').addEventListener('click',()=>native()?.addApiKey());
  $('api-refresh').addEventListener('click',()=>native()?.refreshBalance());
  $('api-remove').addEventListener('click',()=>native()?.deleteApiKey());
  $('api-pricing').addEventListener('click',()=>native()?.openPricing());
  $('companion-manage').addEventListener('click',()=>native()?.companionSettings());
  $('persona-edit').addEventListener('click',()=>native()?.editPersona());
  $('emotion-edit').addEventListener('click',()=>native()?.editEmotion());
  $('sticker-settings').addEventListener('click',()=>native()?.companionSettings());
  $('memory-manage').addEventListener('click',()=>native()?.manageMemory());
  $('todo-open').addEventListener('click',()=>native()?.openTodos());
  function navigate(page){
    if(page!=='pet'&&page!=='settings')return;
    document.body.dataset.page=page;
    document.querySelectorAll('[data-nav]').forEach(b=>{b.classList.toggle('selected',b.dataset.nav===page);b.setAttribute('aria-current',b.dataset.nav===page?'page':'false');});
    window.scrollTo(0,0);
  }
  document.querySelectorAll('[data-nav]').forEach(b=>b.addEventListener('click',()=>{
    if(b.dataset.nav==='pet'||b.dataset.nav==='settings')navigate(b.dataset.nav);else native()?.navigate(b.dataset.nav);
  }));
  navigate('pet');
  window.PetUI={update,updateApi,showPermission,navigate,toast:showToast,back:()=>{
    if(!$('sheet').hidden){closeSheet();return true;}return false;
  }};
  update(current);
  updateApi({ready:false,configured:false});
  native()?.ready();
})();
