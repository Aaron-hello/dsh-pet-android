'use strict';
// Platform adapter only. PetSprite still owns playback, random movement, drag,
// slingshot, collisions and score. No DSH connection or model is simulated.
(() => {
  let active = null;
  let suppressClick = false;
  OPEN_LABEL.standalone = '桌宠设置';
  const unavailable = () => window.AndroidPet?.toast('这项功能需要连接原版 DSH，离线桌宠不提供。');
  const sleepStore = new PetOffline.SleepStore({
    onChange(id, leaf) {
      queueMicrotask(() => {
        if (active?.pet.id === id) { active.setSleep(leaf); reportInteraction(active); }
      });
    },
  });
  const originalMenuAction = PetSprite.prototype.onMenuAction;
  PetSprite.prototype.onMenuAction = function(leaf, ...args) {
    const offlineActions = ['third-party','set-display'];
    // Keep the original sleep refusal rule ahead of the platform availability gate.
    if (offlineActions.includes(leaf?.action) && (!this.sleeping || S.isSleepAllowedAction(leaf.action))) {
      this.closeMenu();
      unavailable();
      return;
    }
    if (leaf?.action === 'reload') { this.closeMenu(); location.reload(); return; }
    return originalMenuAction.call(this, leaf, ...args);
  };
  PetSprite.prototype.showChatFromMenu = function() { window.AndroidPet?.openChat?.(); };
  PetSprite.prototype.toggleTodo = function() { window.AndroidPet?.openTodos?.(); };
  PetSprite.prototype.showBalanceFromMenu = function() { window.AndroidPet?.showBalance?.(); };
  PetSprite.prototype.showWhisperFromMenu = function(image) { window.AndroidPet?.whisper?.(image || ''); };
  // The upstream bridge rounds every rendered position. Keep the spring's
  // integration state fractional so high-refresh screens do not accumulate a
  // rounding dead zone; spring constants and release/throw physics are reused.
  PetSprite.prototype.startDragFollow = function() {
    if (this.dragFollow !== null) return;
    const token = ++this.dragFollowToken;
    const state = {x:this.pos.x,y:this.pos.y,vx:this.dragVel.vx,vy:this.dragVel.vy};
    let last = performance.now();
    const step = () => {
      if (this.dragFollowToken !== token) return;
      const target = this.dragTarget;
      if (!target) { this.dragFollow = null; return; }
      const now = performance.now();
      const dt = Math.min((now-last)/1000,1/30);
      last = now;
      PetOffline.followDrag(state,target,dt,this.physics.throwPower);
      this.dragVel.vx=state.vx;this.dragVel.vy=state.vy;
      this.sendBounds(state.x,state.y);
      this.dragFollow=requestAnimationFrame(step);
    };
    this.dragFollow=requestAnimationFrame(step);
  };
  function reportInteraction(sprite) {
    const sleeping = !!sprite.sleeping;
    const busy = !!(sprite.dragState.active || sprite.chatOpen || sprite.todoOpen || sprite.menuOpen || sprite.frozen);
    const key = sleeping+':'+busy;
    if (sprite._androidInteraction === key) return;
    sprite._androidInteraction = key;
    window.AndroidPet?.interaction?.(sleeping,busy);
  }
  const originalBusy = PetSprite.prototype.syncInputBusy;
  PetSprite.prototype.syncInputBusy = function() {
    originalBusy.call(this);
    reportInteraction(this);
  };
  const originalEnded = PetSprite.prototype.handleEnded;
  PetSprite.prototype.handleEnded = function(...args) {
    const result = originalEnded.apply(this,args);
    reportInteraction(this);
    return result;
  };
  // These are IPC endpoints in the original renderer. The offline build has no
  // third-party process consuming pose / playback reports.
  PetSprite.prototype.markPoseDirty = function() {};
  PetSprite.prototype.reportPlayback = function() {};
  PetSprite.prototype.postSleepAction = function(action) {
    const pet = this.pet;
    const texts = PetOffline.sleepTextsOf(pet);
    if (action === 'wake') {
      sleepStore.wake(pet.id);
      const text = PetOffline.pickSleepText(texts.end);
      if (text !== undefined) this.showWhisper(text, undefined, undefined, 10000, false);
      return;
    }
    const names = PetOffline.pickSleepNames(this.animations);
    if (!names) return;
    if (sleepStore.enter(pet.id, names)) {
      const text = PetOffline.pickSleepText(texts.start);
      if (text !== undefined) this.showWhisper(text, undefined, undefined, 10000, false);
    }
  };

  // Extra visual rectangles include text and score effects in the clipped native
  // window. They do not change physics or select animations.
  PetSprite.prototype.syncInputRegions = function() {
    // One layout read and one bridge update, instead of sending the upstream
    // regions and immediately sending a second, expanded copy.
    const controls = [...document.querySelectorAll(
      '.dsh-pet-menu-column, .dsh-pet-chat, .dsh-pet-todo, .pet-bubble.is-on, .dsh-pet-score, .dsh-pet-score-particle'
    )];
    for (const el of this.inputRegionElements) {
      if (!controls.includes(el)) {
        this.inputRegionResize.unobserve(el);
        this.inputRegionElements.delete(el);
      }
    }
    const rects = controls.map(el => {
      if (!this.inputRegionElements.has(el)) {
        this.inputRegionElements.add(el);
        this.inputRegionResize.observe(el);
      }
      const r = el.getBoundingClientRect();
      return {x:r.x,y:r.y,width:r.width,height:r.height};
    }).filter(r => r.width > 0 && r.height > 0);
    const input = {dragging:this.dragState.active,rects};
    const key = JSON.stringify(input);
    if (this._inputRegions === key) return;
    this._inputRegions = key;
    window.petBridge.setInputRegions(input);
  };

  function installTouch(sprite) {
    let touchMenu = false;
    let menuPress = null;
    const press = PetOffline.createTouchPress({
      stillHolding: () => sprite.dragState.active && !sprite.dragState.dragging,
      onMenu(target) {
        touchMenu = true;
        suppressClick = true;
        sprite.onPointerUp();
        try { sprite.hit.releasePointerCapture(target.pointerId); } catch {}
        sprite.onContextMenu(new MouseEvent('contextmenu', {
          bubbles:true,cancelable:true,clientX:target.x,clientY:target.y,button:2,
        }));
      },
    });
    let start = null;
    sprite.hit.addEventListener('pointerdown', event => {
      if (event.pointerType !== 'touch') return;
      suppressClick = false;
      start = {x:event.screenX,y:event.screenY};
      press.arm({el:sprite.hit,pointerId:event.pointerId,pointerType:event.pointerType,
        x:event.clientX,y:event.clientY});
    });
    sprite.hit.addEventListener('pointermove', event => {
      if (start) press.move(Math.hypot(event.screenX-start.x,event.screenY-start.y));
    });
    for (const name of ['pointerup','pointercancel','lostpointercapture'])
      window.addEventListener(name, () => { press.cancel(); start = null; });
    sprite.hit.addEventListener('click', event => {
      if (!suppressClick) return;
      event.preventDefault(); event.stopImmediatePropagation();
      suppressClick = false;
    }, true);
    // Touch has no hover. Prevent compatibility mouse events from closing the
    // menu after a finger is lifted; invoke the original callbacks exactly once.
    document.addEventListener('pointerdown', event => {
      if (event.pointerType === 'mouse') touchMenu = false;
      const item = event.target.closest?.('.dsh-pet-menu-item');
      if (event.pointerType !== 'touch' || !item) return;
      touchMenu = true;
      menuPress = {item,x:event.screenX,y:event.screenY,id:event.pointerId};
      event.preventDefault();
    }, true);
    document.addEventListener('pointercancel', () => { menuPress = null; }, true);
    document.addEventListener('mouseleave', event => {
      if (touchMenu && event.target.classList?.contains('dsh-pet-menu')) event.stopImmediatePropagation();
    }, true);
    document.addEventListener('click', event => {
      if (touchMenu && event.isTrusted && event.target.closest?.('.dsh-pet-menu-item')) {
        event.preventDefault();event.stopImmediatePropagation();
      }
    }, true);
    document.addEventListener('pointerup', event => {
      if (event.pointerType !== 'touch') return;
      const press = menuPress; menuPress = null;
      if (!press || press.id !== event.pointerId ||
          Math.hypot(event.screenX-press.x,event.screenY-press.y) > 8) return;
      event.preventDefault();
      if (press.item.classList.contains('dsh-pet-menu-branch'))
        press.item.dispatchEvent(new MouseEvent('mouseenter'));
      else press.item.click();
      sprite.syncInputRegions();
    });
  }

  window.PetAndroid = {
    mobilePet(pets) {
      const index = Number(params.get('instanceIndex') || 0);
      const base = pets[CONFIG.petIndex] || pets[0];
      if (!base) return undefined;
      const position = {...base.position};
      if (index === 1) position.corner = 'bottom-left';
      if (index === 2) position.corner = 'bottom-right';
      return {...base, id:'android-'+index, position,
        size:Math.max(120,Math.min(300,Number(params.get('petSize') || 200),VIEW.w * .8))};
    },
    onReady() {
      active = sprites[0];
      if (!active) return;
      // A constructed sprite is not proof that Android has decoded a frame.
      // The service only reports "running" after this plus valid native bounds.
      const reportFirstFrame = () => {
        const front = active.front === 0 ? active.videoA : active.videoB;
        if (front.readyState >= 2 && front.classList.contains('is-front')) {
          window.AndroidPet?.ready(active.pet.id);
        } else {
          setTimeout(reportFirstFrame, 60);
        }
      };
      reportFirstFrame();
      window.__dshPetDebug.offline = true;
      installTouch(active);
      reportInteraction(active);
      // Bubble and menu mutations already have an upstream observer. Include score
      // effect attributes as well, only while an effect is present.
      const sync = () => active.syncInputRegions();
      new MutationObserver(sync).observe(document.body, {childList:true,subtree:true});
      window.PetAndroid.sprite = active;
    },
    closeMenus() { active?.closeMenu(); },
    chatVisibility(visible) {
      if (!active) return;
      active.chatOpen = visible;
      window.__dshPetDebug.chatOpen = visible;
      if (visible) { active.stopMove(); active.stopThrow(); }
      active.syncInputBusy();
    },
    apiReply(text, record = {}) {
      if (!active) return;
      if (active.sleeping) return;
      const preview = text.length > 120 ? text.slice(0,120)+'…' : text;
      if (preview.trim()) active.showWhisper(preview,record.image || undefined,undefined,undefined,!record.action && !active.dragState.active);
      const actions = PetOffline.chatActionNames(active.animations);
      if (record.action && actions.includes(record.action) && !active.dragState.active)
        active.onMenuAction({label:record.action,anim:record.action},false,true);
    },
    companionReply(value) {
      if (!active || !!active.sleeping !== !!value.dream) return;
      active.showWhisper(value.text,value.image || undefined,undefined,10000,
        !value.dream && !active.dragState.active && !active.chatOpen);
    },
    balanceReply(value) {
      if (!active || active.sleeping) return;
      const lines = ['DeepSeek API 余额'];
      if (!value.ready) lines.push('正在读取账户，请稍后再查。');
      else if (value.connecting) lines.push('正在验证密钥，请稍后再查。');
      else if (!value.configured) lines.push('请在桌宠设置中添加 API 密钥。');
      else {
        const rows = value.balance?.rows || [];
        if (value.balanceBusy) lines.push(rows.length ? '正在更新，以下为上次记录…' : '正在查询…');
        else if (value.error) lines.push('查询失败：'+value.error+(rows.length ? '（以下为上次记录）' : ''));
        for (const row of rows) lines.push(row.currency+' '+row.total_balance);
        if (!value.balanceBusy && !value.error && !rows.length) lines.push('账户没有返回余额数据。');
        if (value.balanceTime && rows.length) lines.push('更新于 '+new Date(value.balanceTime).toLocaleTimeString());
      }
      // Use the original balance bubble slot and timer, with text-only custom
      // nodes. No invented percentage, balance animation or chat side effect.
      active.clearWhisper();
      active.showBalanceNow({ok:true,provider:'deepseek-official',shape:'custom',custom:{views:[{
        tag:'div',children:lines.map(text=>({tag:'div',text})),
      }]}},true);
    },
    todoReminder(text) {
      if (!active || active.sleeping || active.dragState.active || active.chatOpen) return;
      active.showWhisper('待办提醒：'+text,undefined,undefined,10000,false);
    },
    setPaused(value) {
      if (!active) return;
      if (value) {
        active.videoA.pause(); active.videoB.pause();
      } else {
        const video = active.front === 0 ? active.videoA : active.videoB;
        video.play().catch(() => {});
      }
    },
    sprite: null,
  };
})();
