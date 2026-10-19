/* 学习入口只管理本地阅读状态，不向业务服务发请求，也不改变 Archify HTML。 */
'use strict';
const content = window.LOTTERY_GUIDE;
const chapters = {
  overview: { title: '先建立一张系统地图', intro: '分清代码里的职责、进程里的组件、数据库里的事实。再带着这张地图进入一次真实调用链。' },
  draw: { title: '沿一次请求，走到抽奖结果', intro: '用同一个 requestId 穿过各层，观察 takeId、orderId 的产生，以及库存、次数、参与记录的变化。' },
  async: { title: 'HTTP 结束之后，责任如何继续', intro: '从中奖订单留下的 Outbox 出发，观察消息投递、唯一账本结算、本地模拟发奖和消费确认。' },
  recovery: { title: '在失败的位置，看见恢复依据', intro: '只画当前源码确实存在的处理路径。沿相同的请求号或订单号，找到可以继续执行的持久事实。' }
};
const $ = selector => document.querySelector(selector);
let chapter = 'overview';
let viewId = '01-layers';
let scenario = 'win';
let position = 0;
let theme = 'light';

// 仅转义进入 HTML 的教学文本；不把文本作为可执行代码或事件属性。
function escapeText(value) {
  return String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
}

// 源码引用按钮始终显示文件名，锚点保留真实行号。
function sourceLinks(references) {
  return '<div class="source-links">' + references.map(reference => {
    // 从构建时保存的映射取得文件标题与稳定锚点。
    const source = content.sources[reference.path];
    // 离线摘录是首选入口，避免文件浏览器把 Java/XML 直接当作下载。
    const target = 'sources.html#' + source.id + '-L' + reference.line;
    return '<a href="' + target + '" target="_blank" rel="noopener">' + escapeText(source.label) + ':' + reference.line + ' ↗</a>';
  }).join('') + '</div>';
}

// 阅读路径由章节和同步分支决定，异步章节始终延续正常中奖订单 O1。
function currentSteps() {
  return chapter === 'async' ? content.async : (scenario === 'lose' ? content.losing : content.sync);
}

// 从教学初始值逐步应用变更，使后退和直接跳步骤也得到一致的数据面板。
function stateAt(index) {
  const snapshot = { ...content.initial };
  // 异步起点已经完成同步中奖，但保留三个订单初始状态。
  if (chapter === 'async') content.sync.forEach(item => Object.assign(snapshot, item.changes));
  // 数据仅由当前步骤之前的变化推导，不依赖隐藏的增量变量。
  currentSteps().slice(0, index + 1).forEach(item => Object.assign(snapshot, item.changes));
  return snapshot;
}

// 构造公开的 Archify 关系深链；相同文件仅改变 fragment 即可聚焦箭头。
function diagramUrl(id, relation = '') {
  // 图与入口脚本采用同一交付版本，避免预览会话保留修订前的图例或标签。
  return 'diagrams/' + id + '/' + id + '.html?theme=' + theme + '&v=20261014-final' + (relation ? '#relation=' + encodeURIComponent(relation) : '');
}

function showDiagram(id, relation = '') {
  // 实际图保持 Archify 校验后的原始字节，入口通过公开 URL 参数管理聚焦。
  const url = diagramUrl(id, relation);
  // 不跨 iframe 读取 DOM，因此直接双击文件也能使用。
  if ($('#diagram-frame').getAttribute('src') !== url) $('#diagram-frame').setAttribute('src', url);
  // 时序图纵向步骤较多，给全图足够展示高度，主页面滚动继续阅读。
  $('#diagram-frame').style.height = id === '03-participation' ? '1590px' : (['04-draw-order','05-outbox-dispatch','06-stock-settlement','07-fulfillment','14-no-prize'].includes(id) ? '1320px' : '1080px');
  // 单独打开保留全部原生查看器功能并使用同一关系聚焦。
  $('#open-diagram').href = url;
  // 标题来自已编写的视图信息，避免另造名称。
  $('#diagram-title').textContent = content.views.find(item => item.id === id).title;
}

function saveLocation() {
  // 使用可分享的本地锚点保存当前章节、图、路径和步骤。
  const params = new URLSearchParams({ chapter, view: viewId, scenario, step: String(position) });
  // replaceState 只影响地址栏，不发起网络请求。
  history.replaceState(null, '', '#' + params.toString());
}

function renderFacts(view) {
  // 组件和异常说明以职责、数据、原因、源码组成同一种卡片。
  $('#facts-panel').innerHTML = view.facts.length ? '<div class="facts-grid">' + view.facts.map(item =>
    '<article class="fact-card"><h3>' + escapeText(item.title) + '</h3><p>' + escapeText(item.body) +
    '</p><p class="fact-why">为什么需要：' + escapeText(item.why) + '</p>' + sourceLinks(item.sources) + '</article>'
  ).join('') + '</div>' : '';
}

function renderState(step) {
  // 当前步骤后的状态与上一步比较，用颜色与文字同时标出变化。
  const snapshot = stateAt(position);
  const prior = position === 0 ? (chapter === 'async' ? { ...content.initial, ...stateAt(-1) } : content.initial) : stateAt(position - 1);
  const fields = [
    ['activityRedis', 'Redis 活动库存', '实时参与额度'],
    ['activityMysql', 'MySQL 活动库存', '当前链路没有逐次扣减'],
    ['prizeRedis', 'Redis 奖品库存', '同步销售预扣'],
    ['prizeMysql', 'MySQL 奖品库存', 'TX3 异步扣减'],
    ['quota', 'MySQL 用户余次数', 'TX1 条件扣减'],
    ['take', 'MySQL 参与记录', 'state：0未消费 / 1已消费'],
    ['order', 'MySQL 中奖订单', '有中奖才创建'],
    ['stockState', 'stock_mq_state', '库存结算消息'],
    ['invoiceState', 'mq_state', '发奖消息'],
    ['grantState', 'grant_state', '本地模拟发奖事实'],
    ['ledger', '全局库结算账本', 'UNIQUE(order_id)'],
    ['lock', '短期请求锁', 'Redis 并发优化'],
    ['dispatchLock', '库存投递标记', 'Redis DISPATCHING · 60秒'],
    ['invoiceLock', '发奖处理标记', 'Redis PROCESSING · 300秒'],
    ['stockAck', '库存消费位点', 'stock topic · 手动 ack'],
    ['invoiceAck', '发奖消费位点', 'invoice topic · 手动 ack']
  ];
  // 每格只展示一种存储事实，活动与奖品库存、消息与发奖状态分别保留。
  const cells = fields.map(([key, label, note]) => {
    const changed = snapshot[key] !== prior[key];
    return '<div class="state-cell' + (changed ? ' changed' : '') + '"><small>' + label + '</small><strong>' +
      escapeText(snapshot[key]) + '</strong><small>' + note + '</small>' +
      (changed ? '<span class="delta">本步：' + escapeText(prior[key]) + ' → ' + escapeText(snapshot[key]) + '</span>' : '') + '</div>';
  }).join('');
  // 教学身份贯穿所有步骤，T1/O1明确是 Snowflake Long 的教学代号。
  $('#data-panel').innerHTML = '<div class="section-heading"><h3>执行到这里，数据是什么样</h3><span>教学示例 · 非运行数据库</span></div>' +
    '<div class="identities"><span>requestId=learn-req-001</span><span>uId=u1</span><span>activityId=100001</span><span>strategyId=10001</span><span>T1=参与ID</span><span>O1=中奖订单ID</span></div>' +
    '<div class="state-grid">' + cells + '</div><div class="response-line">' + escapeText(snapshot.response) + '</div>';
}

function renderStep() {
  const steps = currentSteps();
  // 切换中奖与未中奖路径时，位置限定在选中路径内。
  position = Math.max(0, Math.min(position, steps.length - 1));
  const item = steps[position];
  // 逐步模式选择该步骤实际所属图，不在错误图里假造同名节点。
  viewId = item.diagram;
  const currentView = content.views.find(entry => entry.id === viewId);
  // 更新视图说明与当前分支选择。
  $('#view-summary').textContent = currentView.summary;
  $('#scenario').value = scenario;
  $('#scenario-control').hidden = chapter !== 'draw';
  $('#step-counter').textContent = String(position + 1).padStart(2, '0') + ' / ' + steps.length;
  // 起点与终点禁用越界导航，不产生额外业务分支。
  $('#prev-step').disabled = position === 0;
  $('#next-step').disabled = position === steps.length - 1;
  // 编号栏支持直接定位，也支持按钮与键盘逐步阅读。
  $('#step-rail').innerHTML = steps.map((entry, index) =>
    '<button data-step="' + index + '" class="' + (index === position ? 'active' : index < position ? 'past' : '') +
    '" aria-label="' + escapeText((index + 1) + ' ' + entry.title) + '" title="' + escapeText(entry.title) + '"' +
    (index === position ? ' aria-current="step"' : '') + '>' + (index + 1) + '</button>'
  ).join('');
  // 说明包含位置、方法、输入、操作、输出、原因、下一站和实际代码。
  $('#step-detail').innerHTML = '<div class="step-heading"><span class="layer-chip">' + escapeText(item.layer) +
    '</span><span class="step-marker">执行位置 / ' + String(position + 1).padStart(2, '0') + '</span></div><h2 aria-live="polite">' +
    escapeText(item.title) + '</h2><div class="method">' + escapeText(item.method) + '</div><div class="step-grid">' +
    [['输入', item.inputs], ['正在做什么', item.action], ['输出 / 变化', item.outputs]].map(([label, value]) =>
      '<div><span class="info-label">' + label + '</span><p>' + escapeText(value) + '</p></div>').join('') +
    '</div><div class="step-tail"><div><span class="info-label">为什么需要 / 省略的影响</span><p>' + escapeText(item.why) +
    '</p></div><div><span class="info-label">数据下一站</span><p>' + escapeText(item.next) + '</p></div></div>' + sourceLinks(item.sources);
  // 数据快照与图中同一编号箭头同步更新。
  renderState(item);
  showDiagram(viewId, item.relation);
  // 底部的图选择也显示真正的当前位置。
  document.querySelectorAll('[data-view]').forEach(button => button.classList.toggle('active', button.dataset.view === viewId));
  saveLocation();
}

function render() {
  // 章节标题和侧栏都来源于同一阅读状态。
  $('#chapter-title').textContent = chapters[chapter].title;
  $('#chapter-intro').textContent = chapters[chapter].intro;
  document.querySelectorAll('[data-chapter]').forEach(button => {
    button.classList.toggle('active', button.dataset.chapter === chapter);
    button.setAttribute('aria-current', button.dataset.chapter === chapter ? 'page' : 'false');
  });
  const currentView = content.views.find(entry => entry.id === viewId);
  const tracing = (chapter === 'draw' || chapter === 'async') && !['08-stock-states','09-invoice-states'].includes(viewId);
  // 消息状态图属于概念查看，暂时隐藏步骤面板；回到时序图即可继续追踪。
  $('#trace-toolbar').hidden = !tracing;
  $('#step-detail').hidden = !tracing;
  $('#data-panel').hidden = !tracing;
  $('#view-tabs').innerHTML = content.views.filter(entry => entry.chapter === chapter).map(entry =>
    '<button data-view="' + entry.id + '" class="' + (entry.id === viewId ? 'active' : '') + '">' + entry.title + '</button>'
  ).join('');
  renderFacts(currentView);
  // 追踪视图按步骤渲染，概念视图直接打开其完整图。
  if (tracing) renderStep();
  else {
    $('#view-summary').textContent = currentView.summary;
    showDiagram(viewId);
    saveLocation();
  }
}

// 章节与图解导航在统一事件入口更新阅读状态。
document.addEventListener('click', event => {
  const chapterButton = event.target.closest('[data-chapter]');
  const viewButton = event.target.closest('[data-view]');
  const stepButton = event.target.closest('[data-step]');
  if (chapterButton) {
    chapter = chapterButton.dataset.chapter;
    viewId = content.views.find(entry => entry.chapter === chapter).id;
    scenario = 'win';
    position = 0;
    render();
    window.scrollTo({ top: 0, behavior: 'instant' });
  } else if (viewButton) {
    viewId = viewButton.dataset.view;
    // 未中奖按钮明确选中未中奖路径，正常图按钮选中中奖路径。
    if (chapter === 'draw') scenario = viewId === '14-no-prize' ? 'lose' : 'win';
    const first = currentSteps().findIndex(entry => entry.diagram === viewId);
    if (first >= 0) position = first;
    render();
  } else if (stepButton) {
    position = Number(stepButton.dataset.step);
    renderStep();
  }
});

// 上下步按钮只移动教学位置，不调用抽奖接口。
$('#prev-step').addEventListener('click', () => { position--; renderStep(); });
$('#next-step').addEventListener('click', () => { position++; renderStep(); });
$('#restart').addEventListener('click', () => { position = 0; render(); });
$('#scenario').addEventListener('change', event => {
  scenario = event.target.value;
  position = 0;
  viewId = '03-participation';
  render();
});
// 输入框、选择框和链接操作不占用方向键；步骤工具栏可见才启用快捷导航。
document.addEventListener('keydown', event => {
  if (event.target.closest('input,select,textarea,a') || $('#trace-toolbar').hidden) return;
  const steps = currentSteps();
  if (event.key === 'ArrowRight' && position < steps.length - 1) { event.preventDefault(); position++; renderStep(); }
  if (event.key === 'ArrowLeft' && position > 0) { event.preventDefault(); position--; renderStep(); }
});
$('#theme-toggle').addEventListener('click', () => {
  // 使用公开 theme 参数同步主页面和 Archify 查看器。
  theme = theme === 'light' ? 'dark' : 'light';
  document.documentElement.dataset.theme = theme;
  if (!$('#trace-toolbar').hidden) showDiagram(viewId, currentSteps()[position].relation);
  else showDiagram(viewId);
});

// 地址锚点变化属于同页导航，不会再次运行脚本，因此必须主动恢复阅读状态。
function restoreLocation() {
  // 同一段解析同时用于首次打开、粘贴深链和浏览器的同页返回。
  const params = new URLSearchParams(location.hash.replace(/^#/, ''));
  // 章节为空时回到总入口；只有四个已编写的章节具有可渲染内容。
  chapter = chapters[params.get('chapter')] ? params.get('chapter') : 'overview';
  // 图必须属于该章节，避免图与当前章节标题不一致。
  const selectedView = content.views.find(entry => entry.id === params.get('view') && entry.chapter === chapter);
  // 没有指定图时使用该章第一个图，步骤渲染仍按所属路径决定当前位置。
  viewId = selectedView ? selectedView.id : content.views.find(entry => entry.chapter === chapter).id;
  // 路径和位置取自地址，后续 renderStep 负责当前路径的步骤范围。
  scenario = params.get('scenario') === 'lose' ? 'lose' : 'win';
  position = Number(params.get('step')) || 0;
  // replaceState 不触发 hashchange，所以渲染保存地址不会形成导航循环。
  render();
}
window.addEventListener('hashchange', restoreLocation);
restoreLocation();
