/**
 * 工坊故事图编辑器（story-graph-authoring 3.2：分幕卡片 + 节点/边/变体
 * 清单表单——design Non-Goal 已排除画布，清单内交互为结构化行编辑器）。
 *
 * 编辑模型：graph_json 图级全量保存（幕/节点/边/变体一个 JSON 落库），
 * 前端维护内存副本 sgActs，字段输入按 data-p 路径委托写回，结构增删
 * （加/删幕、行）mutate 后全量重渲——输入不丢（数据在内存，渲染是纯函数）。
 *
 * 校验展示（spec「保存时即时报出，整稿通过报告逐条定位」）：
 *   - 「保存图」= 保存即时校验（guard 语法/词汇表外键），400 的 error
 *     多行文本拆行就地渲染红条（非 alert）；
 *   - 「整稿通过」= 先保存再 POST finalize：200 时 warnings 黄 / repairs
 *     蓝逐条展示并回填修复后图；409 时 errors 红逐条报出（图未落库）。
 *
 * 本文件产出的全局符号：
 *   变量：savedStoryGraphs, currentSgId, sgActs
 *   函数：refreshWorkshopStoryGraphs（随选中世界联动刷新图清单——由
 *         workshop.js 的 worldSelect change 调用）, renderActs,
 *         resetSgForm, saveStoryGraph, finalizeStoryGraph,
 *         showSgReport, hideSgReport
 * 依赖（运行时）：state.js（currentWorldId）；workshop.js（世界切换回调本文件）。
 * 后端契约（3.1）：POST /rpg/workshop/worlds/{wid}/story-graphs 建图；
 *   GET /rpg/workshop/worlds/{wid}/story-graphs 图清单；GET/PUT/DELETE
 *   /rpg/workshop/story-graphs/{gid}；POST /rpg/workshop/story-graphs/{gid}/finalize。
 */

// ===== 状态 =====

/** 当前世界的已保存图清单（列表行：id/name/…全字段） */
let savedStoryGraphs = [];
/** 当前编辑图 ID（null = 新建态，保存时先 POST create 再 PUT 落幕） */
let currentSgId = null;
/** 编辑中的幕数组（graph_json 内存副本，渲染与保存的唯一事实源） */
let sgActs = [];

// ===== 图清单与选中（随世界联动） =====

/** 随选中世界刷新图清单；selectId 用于保存后回选；keepEditor=true 仅刷清单不动编辑器（保存后回调，避免清空当前编辑内容） */
async function refreshWorkshopStoryGraphs(worldId, selectId, keepEditor) {
    const sel = document.getElementById('sgSelect');
    if (!sel) return;
    if (!keepEditor) resetSgForm();
    savedStoryGraphs = [];
    sel.innerHTML = '<option value="">（新建）</option>';
    if (!worldId) return;
    try {
        const res = await fetch(`/rpg/workshop/worlds/${encodeURIComponent(worldId)}/story-graphs`);
        if (!res.ok) return;
        savedStoryGraphs = await res.json();
        savedStoryGraphs.forEach(g => {
            const opt = document.createElement('option');
            opt.value = g.id;
            opt.textContent = g.name || '(未命名)';
            sel.appendChild(opt);
        });
        // 保存后回选当前图（重建 options 丢失选中态，按需恢复）
        if (selectId && savedStoryGraphs.some(g => g.id === selectId)) sel.value = selectId;
    } catch (e) { console.error('加载故事图清单失败', e); }
}

document.getElementById('sgSelect').addEventListener('change', async (e) => {
    const id = e.target.value;
    if (!id) { resetSgForm(); return; }
    try {
        const res = await fetch(`/rpg/workshop/story-graphs/${encodeURIComponent(id)}`);
        if (res.status === 404) { alert('故事图不存在或已删除'); resetSgForm(); return; }
        if (!res.ok) return;
        const g = await res.json();
        loadStoryGraphIntoEditor(g);
    } catch (e) { console.error('加载故事图详情失败', e); }
});

/** 详情载入编辑器：表单头字段 + graph_json 解析进内存副本 */
function loadStoryGraphIntoEditor(g) {
    currentSgId = g.id;
    document.getElementById('sgNameInput').value = g.name || '';
    document.getElementById('sgImproviseSelect').value = g.improviseMode || 'bounded';
    // 4.6 即兴深度上限回填（null/缺省 → 空 = 留空即默认 5）
    document.getElementById('sgDepthLimitInput').value =
            (g.improviseDepthLimit == null) ? '' : String(g.improviseDepthLimit);
    document.getElementById('sgSchemaInput').value = g.stateSchema || '';
    document.getElementById('deleteSgBtn').hidden = false;
    hideSgReport();
    try {
        sgActs = JSON.parse(g.graphJson || '[]');
    } catch (e) {
        sgActs = [];
        showSgReport([{kind: 'err', text: 'graph_json 解析失败：' + e.message + '（已重置为空图，保存将覆盖原内容）'}]);
    }
    renderActs();
}

/** 清空编辑器进新建态（保存时走 POST create） */
function resetSgForm() {
    currentSgId = null;
    sgActs = [];
    document.getElementById('sgNameInput').value = '';
    document.getElementById('sgImproviseSelect').value = 'bounded';
    document.getElementById('sgDepthLimitInput').value = '';
    document.getElementById('sgSchemaInput').value = '';
    document.getElementById('deleteSgBtn').hidden = true;
    hideSgReport();
    renderActs();
    const sel = document.getElementById('sgSelect');
    if (sel) sel.value = '';
}

document.getElementById('newSgBtn').addEventListener('click', resetSgForm);

// ===== 分幕卡片渲染（结构化行编辑器） =====

/** 全量重渲幕卡片墙（数据在内存 sgActs，纯函数渲染） */
function renderActs() {
    const box = document.getElementById('sgActsEditor');
    if (!box) return;
    if (!sgActs.length) {
        box.innerHTML = '<div class="sg-empty">（空图草稿：点下方「＋ 添加幕」开始搭建分支叙事骨架）</div>';
        return;
    }
    box.innerHTML = sgActs.map((a, i) => actCardHtml(a, i)).join('');
}

/** 幕卡片：头字段（id/主题/幕末拍子/变体授权）+ 入口守卫 + 出口轨道 + 节点 + 边 */
function actCardHtml(a, i) {
    return `
    <div class="sg-act-card">
      <div class="sg-act-head">
        <span class="sg-act-badge">幕 ${i + 1}</span>
        <input class="sg-inline" data-p="${i}.id" value="${esc(a.id || '')}" placeholder="actId（如 act1）"/>
        <input class="sg-inline sg-wide" data-p="${i}.theme" value="${esc(a.theme || '')}" placeholder="幕主题（一句话）"/>
        <button type="button" class="sg-mini-btn" data-del="${i}" title="删除本幕（幕内节点/边一并移除）">✕ 删幕</button>
      </div>
      <div class="sg-grid">
        <label class="sg-field">幕末拍子<input data-p="${i}.endingBeat" value="${esc(a.endingBeat || '')}" placeholder="幕末锚点的 beat"/></label>
        <label class="sg-check"><input type="checkbox" data-p="${i}.allowExplicitVariants" ${(a.allowExplicitVariants === true) ? 'checked' : ''}/>本幕允许显式变体（关闭时本幕节点 variants 被忽略）</label>
      </div>
      <div class="sg-guard-row">
        <span class="sg-field-name">入口守卫</span>${guardInputsHtml(`${i}.entryGuard`, a.entryGuard)}
      </div>
      <div class="sg-sub">
        <div class="sg-sub-title">出口收敛轨道（convergeTo：幕末轨道闸，下一幕入口守卫引用此处；整稿要求每幕至少一条）</div>
        ${(a.convergeTo || []).map((c, j) => `
        <div class="sg-row">
          <input class="sg-inline" data-p="${i}.convergeTo.${j}.orbit" value="${esc(c.orbit || '')}" placeholder="轨道名（如 信任线）"/>
          ${guardInputsHtml(`${i}.convergeTo.${j}.guard`, c.guard)}
          <button type="button" class="sg-mini-btn" data-del="${i}.convergeTo.${j}" title="删除本轨道">✕</button>
        </div>`).join('')}
        <button type="button" class="sg-add-btn" data-add="${i}.convergeTo">＋ 轨道</button>
      </div>
      <div class="sg-sub">
        <div class="sg-sub-title">节点（骨架层纯结构：只存拍子不写正文，正文开局后由 GM 物化）</div>
        ${(a.nodes || []).map((n, j) => nodeRowHtml(i, n, j)).join('')}
        <button type="button" class="sg-add-btn" data-add="${i}.nodes">＋ 节点</button>
      </div>
      <div class="sg-sub">
        <div class="sg-sub-title">边（玩家的显式选择；跨幕边由引擎整稿时拼接，不在此编辑）</div>
        ${(a.edges || []).map((e, j) => edgeRowHtml(i, e, j)).join('')}
        <button type="button" class="sg-add-btn" data-add="${i}.edges">＋ 边</button>
      </div>
    </div>`;
}

/** 节点行：平铺（类型/ID/拍子）+ ⚙ 展开高级区（即兴/守卫/进入效果/分化维度/变体） */
function nodeRowHtml(i, n, j) {
    const improvise = n.improvise == null ? '' : String(n.improvise);
    const p = `${i}.nodes.${j}`;
    return `
    <div class="sg-row">
      <select data-p="${p}.type" title="scene 自由场景 / anchor 必经锚点 / ending 结局">
        ${['scene', 'anchor', 'ending'].map(t => `<option value="${t}" ${(n.type || 'scene') === t ? 'selected' : ''}>${t}</option>`).join('')}
      </select>
      <input class="sg-inline" data-p="${p}.id" value="${esc(n.id || '')}" placeholder="n1_01（幕号_序号）"/>
      <input class="sg-inline sg-wide" data-p="${p}.beat" value="${esc(n.beat || '')}" placeholder="beat 拍子（一句话梗概）"/>
      <button type="button" class="sg-mini-btn" data-more title="展开高级字段">⚙</button>
      <button type="button" class="sg-mini-btn" data-del="${p}" title="删除本节点">✕</button>
      <div class="sg-more" hidden>
        <div class="sg-grid">
          <label class="sg-field">节点即兴
            <select data-p="${p}.improvise" title="null=继承作品级 improvise_mode；anchor 强制 false">
              <option value="" ${(improvise === '') ? 'selected' : ''}>继承作品级</option>
              <option value="true" ${(improvise === 'true') ? 'selected' : ''}>开</option>
              <option value="false" ${(improvise === 'false') ? 'selected' : ''}>关</option>
            </select>
          </label>
          <label class="sg-field">分化维度（逗号分隔状态键）<input data-p="${p}.divergenceDimensions" value="${esc((n.divergenceDimensions || []).join(','))}" placeholder="如：trust_信长,suspicion_信长"/></label>
        </div>
        <div class="sg-guard-row"><span class="sg-field-name">入口守卫</span>${guardInputsHtml(`${p}.entryGuard`, n.entryGuard)}</div>
        <label class="sg-field sg-field-full">进入效果 onEnter（JSON）<textarea rows="2" data-p="${p}.onEnter" data-json="1">${esc(jsonText(n.onEnter))}</textarea></label>
        <div class="sg-variants">
          <div class="sg-sub-title">显式变体（幕级授权生效；guard 命中用种子，不命中纯即兴）</div>
          ${(n.variants || []).map((v, k) => variantRowHtml(p, v, k)).join('')}
          <button type="button" class="sg-add-btn" data-add="${p}.variants">＋ 变体</button>
        </div>
      </div>
    </div>`;
}

/** 变体行：guard 三段 + 变体拍子 + 正文种子 */
function variantRowHtml(nodePath, v, k) {
    const p = `${nodePath}.variants.${k}`;
    return `
    <div class="sg-row">
      ${guardInputsHtml(`${p}.guard`, v.guard)}
      <input class="sg-inline" data-p="${p}.beatVariant" value="${esc(v.beatVariant || '')}" placeholder="变体拍子（基调锁定）"/>
      <input class="sg-inline sg-wide" data-p="${p}.bodySeed" value="${esc(v.bodySeed || '')}" placeholder="正文种子（一句话情绪/场面指示，可空）"/>
      <button type="button" class="sg-mini-btn" data-del="${p}" title="删除本变体">✕</button>
    </div>`;
}

/** 边行：平铺（ID/从/到/按钮文案）+ ⚙ 展开高级区（台词/预告/守卫/效果） */
function edgeRowHtml(i, e, j) {
    const p = `${i}.edges.${j}`;
    return `
    <div class="sg-row">
      <input class="sg-inline" data-p="${p}.id" value="${esc(e.id || '')}" placeholder="e1_01"/>
      <input class="sg-inline" data-p="${p}.from" value="${esc(e.from || '')}" placeholder="from 节点"/>
      <span class="sg-arrow">→</span>
      <input class="sg-inline" data-p="${p}.to" value="${esc(e.to || '')}" placeholder="to 节点"/>
      <input class="sg-inline sg-wide" data-p="${p}.label" value="${esc(e.label || '')}" placeholder="按钮文案（短动词短语）"/>
      <button type="button" class="sg-mini-btn" data-more title="展开高级字段">⚙</button>
      <button type="button" class="sg-mini-btn" data-del="${p}" title="删除本边">✕</button>
      <div class="sg-more" hidden>
        <div class="sg-grid">
          <label class="sg-field">玩家台词 quote<input data-p="${p}.quote" value="${esc(e.quote || '')}" placeholder="点击即以这句话作为玩家发言"/></label>
          <label class="sg-field">后果预告 hint<input data-p="${p}.hint" value="${esc(e.hint || '')}" placeholder="可省略的小字预告"/></label>
        </div>
        <div class="sg-guard-row"><span class="sg-field-name">出选项守卫</span>${guardInputsHtml(`${p}.guard`, e.guard)}</div>
        <label class="sg-field sg-field-full">边效果 effects（JSON：数值加减 / flags / player 自由键）<textarea rows="2" data-p="${p}.effects" data-json="1">${esc(jsonText(e.effects))}</textarea></label>
      </div>
    </div>`;
}

/** guard 三段编辑器（key + op 下拉 + value；op=in 时 value 逗号拆数组）。可 ✕ 置 null 清守卫 */
function guardInputsHtml(path, g) {
    const key = g && g.key ? g.key : '';
    const op = g && g.op ? g.op : '';
    let val = '';
    if (g && g.value != null) val = Array.isArray(g.value) ? g.value.join(',') : String(g.value);
    return `
      <input class="sg-inline" data-p="${path}.key" value="${esc(key)}" placeholder="key（词汇表内）"/>
      <select data-p="${path}.op" title="比较算子（value 类型由词汇表声明）">
        ${['', '>=', '<=', '>', '<', '==', 'in'].map(o => `<option value="${o}" ${(op === o) ? 'selected' : ''}>${o || 'op…'}</option>`).join('')}
      </select>
      <input class="sg-inline" data-p="${path}.value" value="${esc(val)}" placeholder="value（in 用逗号分隔）"/>
      <button type="button" class="sg-mini-btn" data-clear="${path}" title="清除守卫（置空=无门槛）">✕</button>`;
}

// ===== 事件委托（字段输入写回内存 / 结构增删重渲 / 高级区展开） =====

const sgActsBox = document.getElementById('sgActsEditor');

/** 文本输入即时写回（JSON textarea 逐字符 try parse，半成品标红直到合法） */
sgActsBox.addEventListener('input', (e) => {
    const t = e.target;
    if (!t.dataset || t.dataset.p === undefined) return;
    if (t.type === 'checkbox' || t.tagName === 'SELECT') return;   // change 事件处理
    applyPath(t.dataset.p, t.value, t);
});

/** checkbox/select 提交时写回（checked / 三态 improvise） */
sgActsBox.addEventListener('change', (e) => {
    const t = e.target;
    if (!t.dataset || t.dataset.p === undefined) return;
    if (t.type === 'checkbox') { setByPath(t.dataset.p, t.checked); return; }
    if (t.tagName === 'SELECT') applyPath(t.dataset.p, t.value, t);
});

/** 增删/展开/清守卫按钮（data-add / data-del / data-more / data-clear 路径） */
sgActsBox.addEventListener('click', (e) => {
    const btn = e.target.closest('button');
    if (!btn || !btn.dataset) return;
    if (btn.dataset.del !== undefined) {
        if (btn.dataset.del.split('.').length === 1 && !confirm('确认删除本幕？幕内节点/边一并移除。')) return;
        delByPath(btn.dataset.del);
        renderActs();
    } else if (btn.dataset.add !== undefined) {
        addByPath(btn.dataset.add);
        renderActs();
    } else if (btn.dataset.clear !== undefined) {
        setByPath(btn.dataset.clear, null);
        renderActs();
    } else if (btn.dataset.more !== undefined) {
        const row = btn.closest('.sg-row');
        const more = row && row.querySelector('.sg-more');
        if (more) more.hidden = !more.hidden;
    }
});

document.getElementById('addSgActBtn').addEventListener('click', () => {
    sgActs.push(newAct(sgActs.length + 1));
    renderActs();
});

/** 新幕缺省值：id 按序号提议（可改），空轨道/节点/边容器 */
function newAct(seq) {
    return {id: 'act' + seq, theme: '', endingBeat: '', entryGuard: null,
            convergeTo: [], allowExplicitVariants: false, nodes: [], edges: []};
}

// ===== 路径写回（data-p：数组下标/对象属性全字符串下标遍历） =====

/** 按路径写回值；字段语义特判（guard value 类型转换 / 分化维度 / JSON / 三态即兴） */
function applyPath(path, raw, target) {
    const seg = path.split('.');
    const field = seg[seg.length - 1];
    // JSON textarea：就地解析（失败标红不写回，保存前扫描阻断）
    if (target && target.dataset.json !== undefined) {
        try {
            const v = raw.trim() === '' ? null : JSON.parse(raw);
            target.classList.remove('sg-json-bad');
            setByPath(path, v);
        } catch (err) {
            target.classList.add('sg-json-bad');
        }
        return;
    }
    if (field === 'divergenceDimensions') {
        setByPath(path, raw.split(',').map(s => s.trim()).filter(Boolean));
        return;
    }
    if (field === 'improvise') {   // 三态：''=继承（null）
        setByPath(path, raw === '' ? null : raw === 'true');
        return;
    }
    if (field === 'value') {   // guard 值：op=in 逗号拆数组，否则智能类型
        const parent = resolvePath(seg.slice(0, -1));
        setByPath(path, parent && parent.op === 'in'
                ? raw.split(',').map(s => s.trim()).filter(Boolean)
                : smartValue(raw));
        return;
    }
    setByPath(path, raw);
}

/** 末段赋值；中间层为 null（如未填的 guard 对象）时物化为 {} */
function setByPath(path, value) {
    const seg = path.split('.');
    let obj = sgActs;
    for (let k = 0; k < seg.length - 1; k++) {
        if (obj[seg[k]] == null) obj[seg[k]] = {};
        obj = obj[seg[k]];
    }
    obj[seg[seg.length - 1]] = value;
}

/** 只读定位（不物化；guard value 类型转换需查同 guard 的 op） */
function resolvePath(seg) {
    let obj = sgActs;
    for (const key of seg) {
        if (obj == null) return null;
        obj = obj[key];
    }
    return obj;
}

/** 智能类型：数字串→number，true/false→布尔，其余原样字符串 */
function smartValue(t) {
    const s = String(t).trim();
    if (s === '') return null;
    if (/^-?\d+(\.\d+)?$/.test(s)) return Number(s);
    if (s === 'true') return true;
    if (s === 'false') return false;
    return s;
}

/** 按路径删除数组元素（顶层路径=删幕） */
function delByPath(path) {
    const seg = path.split('.');
    const arr = resolvePath(seg.slice(0, -1));
    if (Array.isArray(arr)) arr.splice(Number(seg[seg.length - 1]), 1);
}

/** 按路径追加行（convergeTo 轨道 / nodes 节点 / edges 边 / variants 变体 / 顶层幕） */
function addByPath(path) {
    const seg = path === '' ? [] : path.split('.');
    const parent = seg.length ? resolvePath(seg) : sgActs;
    if (!Array.isArray(parent)) return;
    if (seg.length === 0) { parent.push(newAct(parent.length + 1)); return; }
    const kind = seg[seg.length - 1];
    if (kind === 'convergeTo') parent.push({orbit: '', guard: null});
    else if (kind === 'nodes') parent.push({id: '', type: 'scene', beat: '', improvise: null, entryGuard: null, onEnter: null, variants: []});
    else if (kind === 'edges') parent.push({id: '', from: '', to: '', label: '', quote: '', hint: '', guard: null, effects: null});
    else if (kind === 'variants') parent.push({guard: null, beatVariant: '', bodySeed: ''});
}

// ===== 保存 / 整稿通过 / 删除 =====

document.getElementById('saveSgBtn').addEventListener('click', saveStoryGraph);

/**
 * 保存图（图级全量）：新建态先 POST create 拿 id 再 PUT 落幕。
 * 400 校验错误多行拆行就地红条展示（spec「保存时即时报出不落库」）。
 * @returns {Promise<boolean>} 是否成功（finalize 前置依赖）
 */
async function saveStoryGraph() {
    if (!currentWorldId) {
        showSgReport([{kind: 'err', text: '请先在「世界观」tab 选定或新建世界'}]);
        return false;
    }
    // 就地 JSON 字段先扫一遍：红框未修复则阻断（避免半成品 JSON 提交后端）
    const badJson = sgActsBox.querySelectorAll('.sg-json-bad');
    if (badJson.length) {
        showSgReport([{kind: 'err', text: '存在非法 JSON 字段（红框标注的 onEnter/effects），修复后再保存'}]);
        return false;
    }
    const name = document.getElementById('sgNameInput').value.trim();
    if (!name) { showSgReport([{kind: 'err', text: '请输入图名'}]); return false; }
    const mode = document.getElementById('sgImproviseSelect').value;
    // 4.6 即兴深度上限：留空 = null（后端缺省，引擎侧取 5）；正整数校验前置（避免 400 往返）
    const depthLimitRaw = document.getElementById('sgDepthLimitInput').value.trim();
    let depthLimit = null;
    if (depthLimitRaw) {
        depthLimit = parseInt(depthLimitRaw, 10);
        if (!Number.isInteger(depthLimit) || depthLimit < 1) {
            showSgReport([{kind: 'err', text: '即兴深度上限必须是正整数（1 以上）'}]);
            return false;
        }
    }
    const depthLimitParam = depthLimit == null ? null : String(depthLimit);
    const schema = document.getElementById('sgSchemaInput').value.trim() || null;
    if (schema) {
        try { JSON.parse(schema); } catch (e) {
            showSgReport([{kind: 'err', text: '状态词汇表不是合法 JSON：' + e.message}]);
            return false;
        }
    }
    const btn = document.getElementById('saveSgBtn');
    btn.disabled = true;
    try {
        // 新建态：先建壳拿 id（幕随 PUT 落库）
        if (!currentSgId) {
            const res = await fetch(`/rpg/workshop/worlds/${encodeURIComponent(currentWorldId)}/story-graphs`, {
                method: 'POST', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({name, improviseMode: mode, improviseDepthLimit: depthLimitParam, stateSchema: schema})
            });
            if (!res.ok) {
                const b = await res.json().catch(() => null);
                showSgReport([{kind: 'err', text: (b && b.error) || ('建图失败：HTTP ' + res.status)}]);
                return false;
            }
            currentSgId = (await res.json()).id;
            document.getElementById('deleteSgBtn').hidden = false;
        }
        const res = await fetch(`/rpg/workshop/story-graphs/${encodeURIComponent(currentSgId)}`, {
            method: 'PUT', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({name, improviseMode: mode, improviseDepthLimit: depthLimitParam,
                    stateSchema: schema, graphJson: JSON.stringify(sgActs)})
        });
        if (!res.ok) {
            const b = await res.json().catch(() => null);
            showSgReport(splitErrorLines(b && b.error, '保存失败：HTTP ' + res.status));
            return false;
        }
        await refreshWorkshopStoryGraphs(currentWorldId, currentSgId, true);
        showSgReport([{kind: 'ok', text: '✔ 已保存（保存即校验通过：guard 语法与词汇表外键）'}]);
        return true;
    } catch (e) {
        showSgReport([{kind: 'err', text: '保存失败：' + e.message}]);
        return false;
    } finally {
        btn.disabled = false;
    }
}

document.getElementById('finalizeSgBtn').addEventListener('click', finalizeStoryGraph);

/**
 * 整稿通过：先保存（finalize 读库中图），再 POST finalize。
 * 200：repairs/warnings 逐条展示 + 修复后图回填编辑器；409：errors 红条逐条（图未落库）。
 */
async function finalizeStoryGraph() {
    const saved = await saveStoryGraph();
    if (!saved) return;   // 保存失败已就地报出
    const btn = document.getElementById('finalizeSgBtn');
    btn.disabled = true;
    try {
        const res = await fetch(`/rpg/workshop/story-graphs/${encodeURIComponent(currentSgId)}/finalize`, {method: 'POST'});
        const b = await res.json().catch(() => null);
        if (res.ok && b) {
            // 修复后图回填（确定性修复落库的 to 改指等结构变化）
            if (b.graph && b.graph.graphJson) {
                try { sgActs = JSON.parse(b.graph.graphJson); renderActs(); } catch (e) { console.warn('修复后图解析失败', e); }
            }
            const items = [{kind: 'ok', text: '✔ 整稿通过：全图校验链通过，修复后图已落库'}];
            (b.repairs || []).forEach(r => items.push({kind: 'rep', text: '修复：' + r}));
            (b.warnings || []).forEach(w => items.push({kind: 'warn', text: '警告：' + w}));
            showSgReport(items);
        } else if (res.status === 409 && b) {
            showSgReport([{kind: 'err', text: '✘ 整稿通过校验未通过（图未落库，逐条定位）'}]
                    .concat((b.errors || []).map(er => ({kind: 'err', text: er}))));
        } else {
            showSgReport(splitErrorLines(b && b.error, '整稿通过失败：HTTP ' + res.status));
        }
    } catch (e) {
        showSgReport([{kind: 'err', text: '整稿通过失败：' + e.message}]);
    } finally {
        btn.disabled = false;
    }
}

document.getElementById('deleteSgBtn').addEventListener('click', async () => {
    if (!currentSgId) return;
    if (!confirm('确认删除当前故事图？此操作不可撤销。')) return;
    try {
        const res = await fetch(`/rpg/workshop/story-graphs/${encodeURIComponent(currentSgId)}`, {method: 'DELETE'});
        if (res.status === 204) {
            await refreshWorkshopStoryGraphs(currentWorldId);
            return;
        }
        const b = await res.json().catch(() => null);
        if (res.status === 409) {   // 运行副本守卫（对齐世界书有存档 409 惯例）
            showSgReport([{kind: 'err', text: (b && b.error) || '该图存在故事运行副本，不能直接删除'}]);
        } else if (res.status === 404) {
            alert('故事图不存在或已删除');
            await refreshWorkshopStoryGraphs(currentWorldId);
        } else {
            showSgReport([{kind: 'err', text: '删除失败：HTTP ' + res.status}]);
        }
    } catch (e) {
        showSgReport([{kind: 'err', text: '删除失败：' + e.message}]);
    }
});

// ===== 报告区与工具 =====

/** 渲染校验报告：items = [{kind: err|warn|rep|ok, text}]（就地展示，非 alert） */
function showSgReport(items) {
    const box = document.getElementById('sgReportBox');
    if (!box) return;
    box.hidden = false;
    box.innerHTML = items.map(it =>
        `<div class="sg-report-line sg-report-${it.kind}">${esc(it.text)}</div>`).join('');
}

function hideSgReport() {
    const box = document.getElementById('sgReportBox');
    if (box) { box.hidden = true; box.innerHTML = ''; }
}

/** 后端 400 error 多行文本（「保存校验未通过（未落库）：\n节点…」）拆行成红条清单 */
function splitErrorLines(errorText, fallback) {
    if (!errorText) return [{kind: 'err', text: fallback}];
    return errorText.split('\n')
            .map(s => s.trim()).filter(Boolean)
            .map(s => ({kind: 'err', text: s}));
}

/** HTML 转义（模板字面量属性注入防 XSS） */
function esc(s) {
    return String(s).replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));
}

/** JSON 字段反显（null → 空串；对象 → 紧凑序列化） */
function jsonText(v) {
    return v == null ? '' : JSON.stringify(v);
}

// 初始渲染：空图草稿提示（进入 tab 前即就位，Bug 5 修复）
renderActs();
