// Tutorial de bienvenida con Quinci: la primera vez que alguien entra deja su quincena lista paso a paso
// (ingreso, pagos fijos, deudas, cómo ver los montos y un recorrido por la app). Con negocio, suma el paso del negocio.
// Cada "Siguiente" guarda lo que se llenó; "Saltar" o terminar marca onboarded para no volver a mostrarlo.
const TOUR_FIJOS = [['Alquiler', 'hogar'], ['Internet', 'fijo'], ['Luz y agua', 'fijo'], ['Teléfono', 'fijo'], ['Condominio', 'hogar'], ['Colegio', 'educacion']];

function tourSteps() {
  const biz = bizOn();
  return ['hola', ...(biz ? ['negocio'] : []), 'ingreso', 'fijos', 'deudas', 'moneda', 'recorrido', 'listo'];
}

// Se muestra sola a una cuenta nueva (sin datos) que todavía no lo vio.
function tourAuto() {
  const d = S.data;
  if (!d || d.onboarded || S.user?.seller || S.tour) return;
  const fresh = !(d.incomeSources || []).length && !(d.expenses || []).length && !(d.debts || []).length && !(d.recurring || []).length && !(d.sales || []).length;
  if (fresh) S.tour = { i: 0 };
}

function tourEnd(msg) {
  S.tour = null;
  mutate(() => { S.data.onboarded = true }, msg || '');
}

function tourView() {
  if (!S.tour) return '';
  const steps = tourSteps(), k = steps[S.tour.i] || 'listo', n = steps.length, i = S.tour.i;
  const dots = `<div class="tdots">${steps.map((_, j) => `<i class="${j === i ? 'on' : j < i ? 'ok' : ''}"></i>`).join('')}</div>`;
  const q = (mood, prop, text) => `<div class="qrow">${quinciSvg(mood, prop, '', 76)}<div class="qbub"><b>Quinci</b><span>${text}</span></div></div>`;
  const cur = (name, sel = 'USD') => `<label>Moneda<select name="${name}"><option value="USD" ${sel !== 'BS' ? 'selected' : ''}>Dólares ($)</option><option value="BS" ${sel === 'BS' ? 'selected' : ''}>${cap(ctry().p)} (${ctry().t})</option></select></label>`;
  const b = S.data.business || {};
  let body = '', next = 'Siguiente';
  if (k === 'hola') {
    body = q('feliz', '', `¡Hola${S.user?.username ? ', ' + esc(S.user.username) : ''}! Soy <b>Quinci</b> y te voy a acompañar con tu dinero. En unos 2 minutos dejamos tu quincena lista: tu ingreso, tus pagos fijos y tus deudas.`) +
      `<ul class="tlist"><li>💵 Sabes cuánto te queda hasta el próximo cobro, en $ y en ${ctry().t}</li><li>📅 Te aviso antes de que venza un pago</li><li>📉 Armamos un plan para salir de deudas</li>${bizOn() ? '<li>🏪 Y llevamos tu negocio aparte</li>' : ''}</ul><p class="mut" style="margin:0">Puedes saltar cualquier paso y llenarlo después.</p>`;
    next = 'Empezar';
  } else if (k === 'negocio') {
    body = q('feliz', '', `Tu negocio <b>${esc(b.name || '')}</b> ya está creado. Cuéntame a qué te dedicas para hablar tu idioma: si das servicios (abogado, contador, médico, técnico…) verás <b>cobros, clientes y por cobrar</b> en vez de ventas y fiado.`) +
      `<label>¿A qué te dedicas?<select name="type">${Object.entries(BIZ_TYPES).map(([v, l]) => `<option value="${v}" ${v === bizType() ? 'selected' : ''}>${l}</option>`).join('')}</select></label>
       <label>Si das servicios profesionales, tu profesión<select name="profession"><option value="">—</option>${Object.entries(BIZ_PROFS).map(([v, l]) => `<option value="${v}" ${v === (b.profession || '') ? 'selected' : ''}>${l}</option>`).join('')}</select></label>
       <label>¿Con qué tasa trabajas?<select name="rateKind">${Object.entries(BIZ_RATES).map(([v, l]) => `<option value="${v}" ${v === (b.rateKind || 'bcv_usd') ? 'selected' : ''}>${l}</option>`).join('')}</select></label>
       <p class="mut" style="margin:0">El dinero del negocio va aparte de tu quincena. Cuando te pagues a ti, usa «Me pago a mí» y entra como ingreso tuyo.</p>`;
  } else if (k === 'ingreso') {
    body = q('pensando', '', '¿Cuánto cobras y cada cuánto? Con esto calculo cuánto te queda en cada quincena.') +
      `<label>¿Cómo se llama tu ingreso?<input name="name" value="Sueldo" maxlength="80"></label><label>Monto que cobras cada vez<input name="amount" inputmode="decimal" placeholder="0,00"></label>${cur('currency')}
       <label>¿Cada cuánto cobras?<select name="frequency"><option value="quincenal" selected>Cada quincena (15 y 30)</option><option value="semanal">Cada semana</option><option value="mensual">Una vez al mes</option></select></label>
       <label>Si es semanal, ¿qué día?<select name="wday">${WD.map((w, j) => j ? `<option value="${j}" ${j === 5 ? 'selected' : ''}>${cap(w)}</option>` : '').join('')}</select></label>`;
  } else if (k === 'fijos') {
    body = q('pensando', 'calendario', 'Ahora tus pagos fijos de cada mes. Escribe el monto y el día de los que tengas; deja vacíos los demás. Se anotan solos ese día, sin que tengas que acordarte.') +
      `<div class="tfix">${TOUR_FIJOS.map(([n], j) => `<div class="row" style="gap:8px;align-items:flex-end"><label style="flex:2">${n}<input name="fx${j}" inputmode="decimal" placeholder="0,00"></label><label style="flex:1">Día<input name="fd${j}" inputmode="numeric" placeholder="1" maxlength="2"></label></div>`).join('')}</div>${cur('fcur')}
       <p class="mut" style="margin:0">Matrícula, útiles u otros pagos que no se repiten los agregas en Pagos › Compromisos.</p>`;
  } else if (k === 'deudas') {
    body = q('preocupado', 'cartera', '¿Le debes a alguien? Cashea, tarjeta, un préstamo, un familiar… Anótalo y te ayudo a armar un plan para salir.') +
      [0, 1, 2].map((j) => `<div class="row" style="gap:8px;align-items:flex-end"><label style="flex:2">${j ? '' : '¿A quién le debes?'}<input name="de${j}" placeholder="${['Cashea', 'Tarjeta', 'Préstamo'][j]}"></label><label style="flex:1">${j ? '' : 'Debes'}<input name="da${j}" inputmode="decimal" placeholder="0,00"></label><label style="flex:1">${j ? '' : 'Cuota al mes'}<input name="dm${j}" inputmode="decimal" placeholder="0,00"></label></div>`).join('') + cur('dcur') +
      '<p class="mut" style="margin:0">¿No debes nada? ¡Excelente! Toca Siguiente.</p>';
  } else if (k === 'moneda') {
    body = q('feliz', '', `¿Cómo prefieres ver tus montos? Todo se convierte solo con la tasa del día${ctry() === CT.VE ? ' del BCV' : ''}. Lo cambias cuando quieras con el selector de arriba.`) +
      `<div class="chips" id="tcur">${CURS().map(([v, l]) => `<button type="button" class="chip ${S.cur === v ? 'on' : ''}" data-tcur="${v}">${l === '$' ? '$ Dólar' + (ctry() === CT.VE ? ' BCV' : '') : l === '€' ? '€ Euro BCV' : l === 'USDT' ? 'USDT' : l + ' ' + cap(ctry().p)}</button>`).join('')}</div>
       <p class="mut" style="margin:0">Cuando anotes un gasto en ${ctry().t}, puedes decir a qué tasa conseguiste esos ${ctry().p} (BCV, USDT u otra) y ves cuánto te costó de verdad.</p>`;
  } else if (k === 'recorrido') {
    const isAnd = /Android/i.test(navigator.userAgent);
    body = q('celebra', '', 'Te enseño dónde está cada cosa:') +
      `<div class="ttour">
        <div><b>🏠 Inicio</b><span>Cuánto te queda esta quincena, tus próximos pagos y mis avisos.</span></div>
        ${bizOn() ? `<div><b>🏪 Negocio</b><span>${bizSvc() ? 'Cobros, clientes, por cobrar' : 'Ventas, fiado'}, gastos del negocio, caja y ganancia del mes.</span></div>` : ''}
        <div><b>📋 Movimientos</b><span>Tus gastos por día, ingresos, cuentas y gastos fijos.</span></div>
        <div><b>💳 Deudas</b><span>Lo que debes, lo que te deben y tu plan para salir de deudas.</span></div>
        <div><b>📅 Pagos</b><span>Calendario, pagos programados y compromisos de cada mes.</span></div>
        <div><b>⋯ Más</b><span>Tu presupuesto, país, seguridad, sugerencias y tus datos.</span></div>
        <div><b>➕ El botón +</b><span>Anota un gasto en 2 segundos. Si te equivocas de día, puedes cambiar la fecha.</span></div>
        ${isAnd ? '<div><b>📲 En la app de Android</b><span>Comparte el mensaje del banco o del pago móvil con Quincena VE y el gasto se llena solo.</span></div>' : ''}
      </div>`;
  } else {
    const c = calcP();
    body = q('celebra', 'confeti', `¡Listo! Tu quincena quedó configurada.${c.inc > 0 ? ` Por ahora te quedan <b>${show(c.free)}</b> en este periodo.` : ''} Anota tus gastos apenas los hagas y yo te aviso cómo vas.`) +
      `<div class="row" style="gap:8px"><button type="button" class="btn" style="flex:1" data-tfin="gasto">Anotar mi primer gasto</button>${bizOn() ? `<button type="button" class="btn o" style="flex:1" data-tfin="bizventa">${V().add}</button>` : ''}</div>`;
    next = 'Ir al inicio';
  }
  return `<div class="tour" role="dialog" aria-modal="true" aria-label="Tutorial de bienvenida"><form class="tbox" id="f-tour" data-k="${k}">
    <div class="row sp"><span class="mut" style="font-size:13px">Paso ${i + 1} de ${n}</span>${k !== 'listo' ? '<button type="button" class="link" data-tskip="1">Saltar tutorial</button>' : ''}</div>${dots}${body}
    <div class="row" style="gap:8px">${i > 0 && k !== 'listo' ? '<button type="button" class="btn o" style="flex:1" data-tback="1">Atrás</button>' : ''}<button class="btn" style="flex:2">${next}</button></div></form></div>`;
}

// Guarda lo llenado en el paso y avanza.
function tourSave(f) {
  const d = Object.fromEntries(new FormData(f)), k = f.dataset.k, D = S.data, id = 't' + Date.now();
  if (k === 'negocio') {
    D.business = { ...D.business, type: BIZ_TYPES[d.type] ? d.type : 'comercio', profession: BIZ_PROFS[d.profession] ? d.profession : '', rateKind: BIZ_RATES[d.rateKind] ? d.rateKind : 'bcv_usd' };
  } else if (k === 'ingreso') {
    const a = num(d.amount);
    if (!(a > 0)) return;
    const row = { kind: 'salario', name: String(d.name || 'Sueldo').trim().slice(0, 80) || 'Sueldo', amount: a, currency: d.currency, frequency: d.frequency, payDays: d.frequency === 'semanal' ? [parseInt(d.wday) || 5] : d.frequency === 'quincenal' ? [15, 30] : [1] };
    // Si vuelve atrás y cambia el monto, se corrige el mismo ingreso en vez de crear otro.
    const old = (D.incomeSources || []).find((x) => x.id === S.tour.inc);
    if (old) Object.assign(old, row); else { S.tour.inc = id; D.incomeSources.unshift({ id, ...row }) }
  } else if (k === 'fijos') {
    D.recurring = D.recurring || [];
    TOUR_FIJOS.forEach(([name, cat], j) => {
      const a = num(d['fx' + j]);
      if (!(a > 0) || D.recurring.some((r) => nrm(r.description) === nrm(name))) return;
      D.recurring.push({ id: id + '_f' + j, description: name, amount: a, currency: d.fcur, category: cat, day: Math.min(31, Math.max(1, parseInt(d['fd' + j]) || 1)), active: true, lastGenerated: '', mode: 'auto' });
    });
    genRecurring();
  } else if (k === 'deudas') {
    [0, 1, 2].forEach((j) => {
      const ent = String(d['de' + j] || '').trim(), a = num(d['da' + j]), m = num(d['dm' + j]);
      if (!ent || !(a > 0) || (D.debts || []).some((x) => nrm(x.entity) === nrm(ent))) return;
      D.debts.unshift({ id: id + '_d' + j, type: /cashea/i.test(ent) ? 'cashea' : 'otro', amount: a, currency: d.dcur, entity: ent.slice(0, 120), description: '', paid: 0, date: new Date().toISOString(), ...(m > 0 ? { minPayment: m } : {}), interestKnown: false });
    });
  }
}

document.addEventListener('submit', (e) => {
  if (e.target.id !== 'f-tour') return;
  e.preventDefault(); e.stopImmediatePropagation();
  try { tourSave(e.target) } catch (_) { }
  const steps = tourSteps();
  if (steps[S.tour.i] === 'listo') { tourEnd('¡Bienvenido a Quincena VE!'); return }
  S.tour.i = Math.min(steps.length - 1, S.tour.i + 1);
  render(); save();
}, true);

document.addEventListener('click', (e) => {
  const t = e.target.closest('[data-tskip],[data-tback],[data-tcur],[data-tfin],[data-tour]');
  if (!t) return;
  const D = t.dataset;
  if (D.tskip) { tourEnd('Puedes ver el tutorial cuando quieras en Más.'); return }
  if (D.tback) { S.tour.i = Math.max(0, S.tour.i - 1); render(); return }
  if (D.tcur) { S.cur = D.tcur; S.data.currency = D.tcur; document.querySelectorAll('[data-tcur]').forEach((c) => c.classList.toggle('on', c === t)); save(); return }
  if (D.tfin) { S.tour = null; S.data.onboarded = true; save(); S.sheet = D.tfin; render(); return }
  if (D.tour) { S.tour = { i: 0 }; render() }
}, true);
