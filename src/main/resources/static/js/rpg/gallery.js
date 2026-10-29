/**
 * 世界卡片墙共享渲染器（world-card-gallery design D1/D8/D11）。
 * <p>
 * 唯一卡片渲染实现，根首页橱窗与 RPG 工作台墙两层共用：
 * 封面 img + 标题 + 两行 line-clamp 简介（无 chips 标签行）。
 * 封面 404/破图时回落 CSS 渐变底（worldId 哈希取色）+ 世界名首字，墙永不开天窗。
 * <p>
 * 产出全局符号：fetchWorldCards / renderWallSkeleton / renderWorldWall
 * 依赖：无（纯 DOM，两页均可独立加载）
 */

/**
 * 拉取画廊卡片；非 2xx 抛错由调用方决定提示方式。
 * @param {string} [scope] 'works' = 只取纯沙盒行（作品墙口径，works-view-and-world-publish D9）；
 *                         缺省 = 旧行为（本租户全部世界 + 未物化预设殿后，过渡期兼容）
 */
async function fetchWorldCards(scope) {
    const url = scope ? '/rpg/worlds/gallery?scope=' + encodeURIComponent(scope)
                      : '/rpg/worlds/gallery';
    const res = await fetch(url);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return await res.json();
}

/**
 * 拉取根首页橱窗卡片（works-view-and-world-publish 5.2）：全平台在架已发布世界
 * （source='published'，id=packId、originWorldId/publisherSelf/entryCount 供导入分流）
 * + 预设世界（source='preset'）；非 2xx 抛错由调用方决定提示方式。
 */
async function fetchShowcase() {
    const res = await fetch('/rpg/worlds/showcase');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return await res.json();
}

/** 骨架卡占位（橱窗 client-fetch 首屏防闪空） */
function renderWallSkeleton(container, count) {
    const n = count || 8;
    container.innerHTML = '';
    for (let i = 0; i < n; i++) {
        const sk = document.createElement('div');
        sk.className = 'world-card skeleton';
        sk.innerHTML = '<div class="world-card-cover"></div>'
            + '<div class="world-card-body"><div class="sk-line sk-title"></div>'
            + '<div class="sk-line"></div><div class="sk-line"></div></div>';
        container.appendChild(sk);
    }
}

/** worldId 哈希 → 色相（兜底渐变取色，同 id 恒同色，墙观感稳定） */
function worldHue(id) {
    let h = 0;
    const s = String(id || '');
    for (let i = 0; i < s.length; i++) {
        h = (h * 31 + s.charCodeAt(i)) >>> 0;
    }
    return h % 360;
}

/**
 * 渲染卡片墙（调用前容器已存在）。
 *
 * @param container 墙容器元素
 * @param cards     画廊 DTO 列表 {id, name, blurb, coverUrl, source, presetKey}
 *                 （showcase DTO 另有 originWorldId/publisherTenantId/publisherSelf/entryCount）
 * @param opts      {showHintCard: 无沙盒世界时墙尾追加「去创建」提示卡（仅工作台层）,
 *                   onHintClick: 提示卡点击回调,
 *                   hrefFor(card): 返回 href 时卡片渲染为 <a>（预设深链；返回 falsy 落回 div）,
 *                   publisherLabelFor(card): 返回非空文本时卡身加发布者标识行（橱窗已发布卡）,
 *                   onCardClick(card, el): 卡片点击回调（无 href 卡，点击拦截默认跳转）}
 */
function renderWorldWall(container, cards, opts) {
    const o = opts || {};
    container.innerHTML = '';
    (cards || []).forEach(card => container.appendChild(buildWorldCard(card, o)));
    const hasSandbox = (cards || []).some(c => c.source === 'sandbox');
    if (o.showHintCard && !hasSandbox) {
        container.appendChild(buildHintCard(o.onHintClick));
    }
}

/** 单张世界卡：封面（img + 渐变首字兜底层）+ 标题 + 发布者标识行（可选）+ 两行简介 + 隐藏 chips 槽 */
function buildWorldCard(card, opts) {
    const o = opts || {};
    let href = null;
    if (o.hrefFor) href = o.hrefFor(card) || null;   // falsy 落回 div（published 卡走 JS 交互）
    const el = document.createElement(href ? 'a' : 'div');
    el.className = 'world-card';
    el.dataset.id = card.id;
    el.dataset.source = card.source;
    if (card.presetKey) el.dataset.presetKey = card.presetKey;
    if (href) el.href = href;

    const hue = worldHue(card.id);
    const cover = document.createElement('div');
    cover.className = 'world-card-cover';
    const fallback = document.createElement('div');
    fallback.className = 'world-card-fallback';
    fallback.style.background = 'linear-gradient(160deg, hsl(' + hue + ' 42% 34%), hsl('
        + ((hue + 46) % 360) + ' 52% 15%))';
    fallback.textContent = (card.name || '界').charAt(0);
    const img = document.createElement('img');
    img.alt = card.name || '';
    img.loading = 'lazy';
    img.src = card.coverUrl;
    // 404/解码失败即移除 img，露出下层渐变首字兜底（不破图、不开天窗）
    img.addEventListener('error', () => img.remove());
    cover.appendChild(fallback);
    cover.appendChild(img);

    const body = document.createElement('div');
    body.className = 'world-card-body';
    const name = document.createElement('h3');
    name.className = 'world-card-name';
    name.textContent = card.name || '未命名世界';
    const blurb = document.createElement('p');
    blurb.className = 'world-card-blurb';
    blurb.textContent = card.blurb || '';
    body.appendChild(name);
    // 发布者标识行（橱窗已发布卡，works-view-and-world-publish 5.2）：
    // publisherSelf 卡绿色高亮「你发布的」，他人卡灰色「共享世界」
    if (o.publisherLabelFor) {
        const label = o.publisherLabelFor(card);
        if (label) {
            const pub = document.createElement('p');
            pub.className = 'world-card-pub' + (card.publisherSelf ? ' self' : '');
            pub.textContent = label;
            body.appendChild(pub);
        }
    }
    body.appendChild(blurb);

    // 角色 chips 槽（仅工作台层沙盒卡展开使用，橱窗恒隐藏）
    const chips = document.createElement('div');
    chips.className = 'world-card-chips';
    chips.hidden = true;

    el.appendChild(cover);
    el.appendChild(body);
    el.appendChild(chips);

    if (o.onCardClick) {
        el.addEventListener('click', ev => {
            // href 卡（预设深链）走默认跳转（保留中键/新标签打开能力）
            if (el.tagName === 'A' && el.getAttribute('href')) return;
            ev.preventDefault();
            o.onCardClick(card, el);
        });
    }
    return el;
}

/** 「创建第一个世界」提示卡（design D11，仅工作台层且无沙盒世界时） */
function buildHintCard(onHintClick) {
    const el = document.createElement('div');
    el.className = 'world-card world-card-hint';
    el.innerHTML = '<div class="world-card-cover hint-cover">✍</div>'
        + '<div class="world-card-body"><h3 class="world-card-name">创建第一个世界</h3>'
        + '<p class="world-card-blurb">写下世界观与角色，回到这里开局。</p></div>';
    if (onHintClick) {
        el.addEventListener('click', ev => {
            ev.preventDefault();
            onHintClick();
        });
    }
    return el;
}
