// Quinci: la mascota de Quincena VE. Una moneda con su hojita verde que reacciona a lo que pasa con tu dinero.
// quinciSvg dibuja el personaje; quinciNow decide qué dice según tus datos; quinciPop lo muestra un momento tras una acción.
const QMOODS = ['feliz', 'celebra', 'preocupado', 'alerta', 'pensando'];

function quinciSvg(mood = 'feliz', prop = '', label = '', size = 96) {
  const m = QMOODS.includes(mood) ? mood : 'feliz';
  const eyes = {
    feliz: '<ellipse cx="47" cy="56" rx="5" ry="6.5" fill="#3B2A05"/><ellipse cx="73" cy="56" rx="5" ry="6.5" fill="#3B2A05"/><circle cx="49" cy="53.5" r="1.8" fill="#fff"/><circle cx="75" cy="53.5" r="1.8" fill="#fff"/>',
    celebra: '<path d="M41 57q6-8 12 0M67 57q6-8 12 0" stroke="#3B2A05" stroke-width="3.4" fill="none" stroke-linecap="round"/>',
    preocupado: '<ellipse cx="47" cy="58" rx="4.6" ry="6" fill="#3B2A05"/><ellipse cx="73" cy="58" rx="4.6" ry="6" fill="#3B2A05"/><circle cx="48.5" cy="55.5" r="1.6" fill="#fff"/><circle cx="74.5" cy="55.5" r="1.6" fill="#fff"/><path d="M40 47l12 4M80 47l-12 4" stroke="#3B2A05" stroke-width="3" stroke-linecap="round"/>',
    alerta: '<circle cx="47" cy="56" r="7" fill="#fff" stroke="#3B2A05" stroke-width="2"/><circle cx="73" cy="56" r="7" fill="#fff" stroke="#3B2A05" stroke-width="2"/><circle cx="47" cy="56" r="3.6" fill="#3B2A05"/><circle cx="73" cy="56" r="3.6" fill="#3B2A05"/><path d="M40 45l12-2M80 45l-12-2" stroke="#3B2A05" stroke-width="3" stroke-linecap="round"/>',
    pensando: '<ellipse cx="49" cy="55" rx="4.6" ry="6" fill="#3B2A05"/><ellipse cx="75" cy="55" rx="4.6" ry="6" fill="#3B2A05"/><circle cx="51" cy="52" r="1.6" fill="#fff"/><circle cx="77" cy="52" r="1.6" fill="#fff"/><path d="M42 46q6-4 12-1" stroke="#3B2A05" stroke-width="3" fill="none" stroke-linecap="round"/>',
  }[m];
  const mouth = {
    feliz: '<path d="M50 70q10 9 20 0" stroke="#3B2A05" stroke-width="3.4" fill="none" stroke-linecap="round"/>',
    celebra: '<path d="M48 67q12 0 24 0q-2 13-12 13t-12-13z" fill="#7A2E12"/><path d="M53 75q7 4 14 0" fill="#F07A6A"/>',
    preocupado: '<path d="M50 76q5-6 10 0t10 0" stroke="#3B2A05" stroke-width="3.2" fill="none" stroke-linecap="round"/>',
    alerta: '<ellipse cx="60" cy="74" rx="5" ry="6" fill="#7A2E12"/>',
    pensando: '<path d="M53 73h13" stroke="#3B2A05" stroke-width="3.2" stroke-linecap="round"/>',
  }[m];
  const arms = m === 'celebra'
    ? '<path d="M24 58q-10-10-8-22M96 58q10-10 8-22" stroke="#C98A00" stroke-width="5" fill="none" stroke-linecap="round"/>'
    : '<path d="M22 66q-8 4-9 12M98 66q9 2 12 9" stroke="#C98A00" stroke-width="5" fill="none" stroke-linecap="round"/>';
  const esc2 = (t) => String(t || '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const short = esc2(String(label || '').slice(0, 12));
  const props = {
    cartera: `<g transform="rotate(-8 104 88)"><rect x="82" y="74" width="40" height="28" rx="6" fill="#6B3F1D"/><rect x="82" y="74" width="40" height="9" rx="4" fill="#8A5428"/><circle cx="114" cy="90" r="3" fill="#E7C27A"/><text x="101" y="94" font-size="7.5" font-family="Figtree,Arial" font-weight="700" fill="#FFE9C2" text-anchor="middle">${short}</text></g>`,
    calendario: `<g><rect x="90" y="70" width="28" height="28" rx="5" fill="#fff" stroke="#D5DDD7" stroke-width="1.5"/><rect x="90" y="70" width="28" height="9" rx="4" fill="#CF142B"/><text x="104" y="94" font-size="12" font-family="Figtree,Arial" font-weight="800" fill="#12211B" text-anchor="middle">${short || '!'}</text></g>`,
    confeti: '<g class="q-confetti"><rect x="12" y="12" width="6" height="3" rx="1" fill="#FFCC00" transform="rotate(25 15 13)"/><rect x="100" y="10" width="6" height="3" rx="1" fill="#00247D" transform="rotate(-30 103 11)"/><rect x="20" y="96" width="6" height="3" rx="1" fill="#CF142B" transform="rotate(40 23 97)"/><circle cx="106" cy="30" r="2.5" fill="#12936A"/><circle cx="14" cy="36" r="2.5" fill="#CF142B"/><rect x="96" y="100" width="6" height="3" rx="1" fill="#FFCC00" transform="rotate(-20 99 101)"/></g>',
    lupa: '<g><circle cx="104" cy="84" r="9" fill="#E3F1EA" stroke="#46564E" stroke-width="3"/><path d="M110 91l7 8" stroke="#46564E" stroke-width="4" stroke-linecap="round"/></g>',
  }[prop] || '';
  const sweat = m === 'preocupado' ? '<path d="M86 40q4 7 0 9q-4-2 0-9z" fill="#7CC6F2"/>' : '';
  return `<svg class="quinci q-${m}" width="${size}" height="${size}" viewBox="0 0 120 120" role="img" aria-label="Quinci, la mascota de Quincena VE">
<defs><radialGradient id="qg" cx="40%" cy="35%" r="70%"><stop offset="0" stop-color="#FFE27A"/><stop offset=".6" stop-color="#FFC530"/><stop offset="1" stop-color="#E9A100"/></radialGradient></defs>
<ellipse cx="60" cy="112" rx="28" ry="4.5" fill="#0B6B4D" opacity=".15"/>
<path class="q-leaf" d="M60 22q-2-10 6-16q10 2 8 12q-6 6-14 4z" fill="#12936A"/><path class="q-leaf-vein" d="M61 21q4-6 9-11" stroke="#0B6B4D" stroke-width="1.6" fill="none"/>
${arms}<circle cx="60" cy="62" r="40" fill="url(#qg)" stroke="#C98A00" stroke-width="2.5"/><circle cx="60" cy="62" r="33" fill="none" stroke="#FFE9A0" stroke-width="2" opacity=".8"/>
<circle cx="40" cy="69" r="5" fill="#F59A8B" opacity=".55"/><circle cx="80" cy="69" r="5" fill="#F59A8B" opacity=".55"/>
${eyes}${mouth}${sweat}${props}</svg>`;
}

// Qué dice Quinci ahora: el primer caso que aplique, de lo más urgente a lo más alegre.
function quinciNow() {
  const d = S.data, cp = calcP(), c = calc(), today = ymd(new Date());
  const name = (x) => x.entity || x.description || 'tu deuda';
  if (!(d.incomeSources || []).length && !(d.expenses || []).length)
    return { mood: 'feliz', prop: '', text: '¡Hola! Soy Quinci y te acompaño con tu quincena. Empecemos: agrega tu ingreso.', btn: ['Agregar ingreso', 'data-sheet="ingreso"'] };
  if (cp.inc > 0 && cp.free < 0)
    return { mood: 'preocupado', prop: 'lupa', text: `Este periodo sale más de lo que entra: te faltan ${show(-cp.free)}. Busquemos qué recortar.`, btn: ['Ver mis gastos', 'data-tab="movimientos"'] };
  const cm = comprometido();
  if (cm.pct !== null && cm.pct >= 60) {
    const big = (d.debts || []).filter((x) => x.amount - (x.paid || 0) > .005).sort((a, b) => usd(b.amount - (b.paid || 0), b.currency) - usd(a.amount - (a.paid || 0), a.currency))[0];
    return { mood: 'preocupado', prop: 'cartera', label: big ? name(big) : 'Deudas', text: `Tu sueldo de las próximas quincenas se va en ${cm.pct} % en deudas y pagos${big ? `. La más pesada: «${name(big)}»` : ''}. Armemos un plan.`, btn: ['Ver mi plan', 'data-tab="deudas"'] };
  }
  const soon = [];
  const in3 = ymd(new Date(Date.now() + 3 * 864e5));
  for (const p of d.plannedDebtPayments || []) if (p.status === 'planned' && p.scheduledDate >= today && p.scheduledDate <= in3) soon.push({ d: p.scheduledDate, t: p.entity || 'un pago', a: money(p.amount, p.currency) });
  const key = today.slice(0, 7), last = new Date(+key.slice(0, 4), +key.slice(5, 7), 0).getDate();
  for (const r of d.recurring || []) {
    if (r.active === false || r.mode !== 'manual' || recPaid(r, key)) continue;
    const k = key + '-' + String(Math.min(r.day || 1, last)).padStart(2, '0');
    if (k >= today && k <= in3) soon.push({ d: k, t: r.description, a: money(r.amount, r.currency) });
  }
  for (const x of d.schoolExpenses || []) if (x.dueDate && x.dueDate >= today && x.dueDate <= in3) soon.push({ d: x.dueDate, t: x.description, a: money(x.amount, x.currency) });
  soon.sort((a, b) => a.d.localeCompare(b.d));
  if (soon.length) {
    const s = soon[0], when = s.d === today ? 'Hoy' : dayLabel(s.d);
    return { mood: 'alerta', prop: 'calendario', label: String(+s.d.slice(8, 10)), text: `${when} vence «${s.t}» (${s.a})${soon.length > 1 ? ` y ${soon.length - 1} pago${soon.length > 2 ? 's' : ''} más` : ''}. ¡Que no te agarre desprevenido!`, btn: ['Ver pagos', 'data-tab="pagos"'] };
  }
  const r = perRange(), b = d.budgets || {}, f = r.h === 0 ? 1 : .5, pc = periodCats();
  const over = Object.keys(CATS).filter((k) => b[k] > 0 && (pc[k] || 0) > b[k] * f).sort((x, y) => (pc[y] - b[y] * f) - (pc[x] - b[x] * f))[0];
  if (over) return { mood: 'alerta', prop: '', text: `Te pasaste del presupuesto de ${CATS[over]} por ${show(pc[over] - b[over] * f)}. Frenemos un poquito ahí.`, btn: ['Ver presupuesto', 'data-sheet="info:pres"'] };
  const days = new Set((d.expenses || []).map((e) => localDay(e.date)));
  let streak = 0;
  for (let i = 0; i < 60; i++) { if (days.has(ymd(new Date(Date.now() - i * 864e5)))) streak++; else if (i > 0 || streak) break; }
  if (streak >= 3) return { mood: 'celebra', prop: 'confeti', text: `¡Llevas ${streak} días seguidos anotando tus gastos! Así se controla la quincena.` };
  if (!days.has(today) && new Date().getHours() >= 18) return { mood: 'pensando', prop: '', text: '¿Gastaste algo hoy? Anótalo en 2 segundos y no se te olvida.', btn: ['Anotar gasto', 'data-sheet="gasto"'] };
  if (c.debt > .005 && plan().configured && plan().months) return { mood: 'feliz', prop: '', text: `Vas en camino: si sigues el plan, terminas tus deudas en ${plan().months} ${plan().months === 1 ? 'mes' : 'meses'}.` };
  return { mood: 'feliz', prop: '', text: cp.inc > 0 ? `¡Vas bien! Te quedan ${show(cp.free)} en este periodo.` : 'Todo en orden por aquí. ¡Sigue anotando!' };
}

function quinciHidden() { try { return localStorage.getItem('qquinci') === ymd(new Date()) } catch (_) { return false } }

function quinciCard() {
  if (quinciHidden()) return '';
  const q = quinciNow();
  return `<div class="card qcard"><button class="link qx" data-quincix="1" aria-label="Ocultar a Quinci por hoy">×</button><div class="qrow">${quinciSvg(q.mood, q.prop, q.label, 84)}<div class="qbub"><b>Quinci</b><span>${esc(q.text)}</span>${q.btn ? `<button class="btn s" ${q.btn[1]}>${q.btn[0]}</button>` : ''}</div></div></div>`;
}

// Aparece un momento abajo con una reacción (tras anotar un gasto, abonar a una deuda, etc.).
function quinciPop(mood, text, prop = '', label = '') {
  document.querySelector('.qpop')?.remove();
  const el = document.createElement('div');
  el.className = 'qpop';
  el.setAttribute('role', 'status');
  el.innerHTML = `${quinciSvg(mood, prop, label, 64)}<div class="qbub"><span>${esc(text)}</span></div>`;
  document.body.appendChild(el);
  requestAnimationFrame(() => el.classList.add('in'));
  setTimeout(() => { el.classList.remove('in'); setTimeout(() => el.remove(), 400); }, 4200);
  el.addEventListener('click', () => el.remove());
}

// Reacción después de anotar un gasto: si con él te pasaste del presupuesto, lo dice; si no, te anima.
function quinciAfterGasto(cat) {
  const r = perRange(), b = S.data.budgets || {}, f = r.h === 0 ? 1 : .5, pc = periodCats(), lim = (b[cat] || 0) * f;
  if (lim > 0 && (pc[cat] || 0) > lim) return quinciPop('alerta', `Uy, con este gasto pasaste tu presupuesto de ${CATS[cat]}.`);
  if (lim > 0 && (pc[cat] || 0) > lim * .8) return quinciPop('pensando', `Ojo: ya usaste el ${Math.round((pc[cat] || 0) / lim * 100)} % de tu presupuesto de ${CATS[cat]}.`);
  const cp = calcP(), msgs = ['¡Anotado! Así no se te escapa nada.', '¡Listo! Un gasto menos por recordar.', '¡Bien hecho! Anotar es el primer paso.'];
  quinciPop('feliz', cp.inc > 0 ? `¡Anotado! Te quedan ${show(cp.free)} en este periodo.` : msgs[Math.floor(Math.random() * msgs.length)]);
}

// Después de guardar un formulario: gasto → reacción al presupuesto; abono que termina una deuda → celebración.
function quinciReact(k) {
  const D = S.data;
  if (k === 'gasto' && D.expenses[0]) return quinciAfterGasto(D.expenses[0].category);
  if (k === 'abono') {
    const x = (D.debts || []).find((y) => String(y.id) === S.lastArg);
    if (x && x.amount - (x.paid || 0) <= .005) return quinciPop('celebra', `¡Terminaste de pagar «${x.entity || 'tu deuda'}»! Una menos.`, 'confeti');
    if (x) return quinciPop('feliz', `¡Abono registrado! A «${x.entity || 'tu deuda'}» le falta ${money(x.amount - (x.paid || 0), x.currency)}.`, 'cartera', x.entity);
  }
  if (k === 'plan') { const P = plan(); if (P.months) quinciPop('celebra', `¡Plan listo! Si lo sigues, terminas tus deudas en ${P.months} ${P.months === 1 ? 'mes' : 'meses'}.`, 'confeti'); }
}
