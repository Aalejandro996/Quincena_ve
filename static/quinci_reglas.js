// Reglas de Quinci: observaciones personalizadas con los datos de cada persona, pensadas para el día a día del venezolano
// (quincena, tasa BCV y USDT, pago móvil, Cashea, aguinaldos, inicio de clases, delivery, gastos hormiga…).
// No hay inteligencia artificial ni se envía nada fuera de la cuenta: todo se calcula aquí, con lo que la persona anotó.
// Cada regla devuelve { id, pri, cd, mood, prop, label, text, btn } o null.
//   pri: importancia (0–100; lo urgente de quinci.js va antes de todo esto).
//   cd:  días de descanso: después de mostrarse, la regla no vuelve a salir hasta pasados esos días.
// La misma lógica está en Android (QuinciReglas.kt). Si se cambia una regla, cambiarla en los dos lados.

const QR = (() => {
  const DAY = 864e5;
  const D = () => S.data || {};
  const ve = () => (typeof isVE === 'function' ? isVE() : true);
  const V = (a, b) => (ve() ? a : b);
  const pad = (n) => String(n).padStart(2, '0');
  const key = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const today = () => new Date();
  const dayKey = (s) => { try { return typeof localDay === 'function' ? localDay(s) : String(s || '').slice(0, 10); } catch (_) { return String(s || '').slice(0, 10); } };
  const U = (a, c) => usd(Number(a) || 0, c);
  const low = (t) => String(t || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
  const has = (t, words) => words.some((w) => low(t).includes(w));
  const plural = (n, s, p) => `${n} ${n === 1 ? s : p}`;

  // Quincena actual y la anterior (siempre las de hoy, aunque la persona esté mirando otro periodo).
  function ranges() {
    const n = today(), y = n.getFullYear(), m = n.getMonth(), last = new Date(y, m + 1, 0).getDate(), h = n.getDate() <= 15 ? 1 : 2;
    const f = (yy, mm, dd) => `${yy}-${pad(mm + 1)}-${pad(dd)}`;
    const cur = h === 1 ? { a: f(y, m, 1), b: f(y, m, 15), end: 15 } : { a: f(y, m, 16), b: f(y, m, last), end: last };
    const pm = new Date(y, m, 0);  // último día del mes anterior
    const prev = h === 2 ? { a: f(y, m, 1), b: f(y, m, 15) } : { a: f(pm.getFullYear(), pm.getMonth(), 16), b: f(pm.getFullYear(), pm.getMonth(), pm.getDate()) };
    const month = { a: f(y, m, 1), b: f(y, m, last) };
    return { cur, prev, month, h, day: n.getDate(), last, daysLeft: Math.max(1, cur.end - n.getDate() + 1), dow: n.getDay(), m };
  }

  const inR = (e, r) => { const k = dayKey(e.date); return k >= r.a && k <= r.b; };
  const exps = (r) => (D().expenses || []).filter((e) => inR(e, r));
  const sum = (l) => l.reduce((n, e) => n + U(e.amount, e.currency), 0);
  const byCat = (l) => { const c = {}; for (const e of l) c[e.category] = (c[e.category] || 0) + U(e.amount, e.currency); return c; };
  const left = (x) => Math.max(0, (Number(x.amount) || 0) - (Number(x.paid) || 0));

  // Ingreso de la quincena actual: el mismo cálculo de la pantalla de Inicio si se está viendo el periodo de hoy.
  function quincenaIncome(R) {
    try {
      const p = per();
      if (p.y === today().getFullYear() && p.m === R.m && p.h === R.h) return calcP().inc || 0;
    } catch (_) {}
    try { return (calc().inc || 0) / 2; } catch (_) { return 0; }
  }

  const rules = [];
  const rule = (f) => rules.push(f);

  // ───────────────────────── Cobro y fin de quincena
  rule((C) => {  // hoy es día de cobro
    const q = (D().incomeSources || []).filter((s) => s.frequency === 'quincenal'), dd = C.R.day;
    if (!q.length || !(dd === 15 || dd === C.R.last || dd === 30)) return null;
    const fix = (D().recurring || []).filter((r) => r.active !== false).reduce((n, r) => n + U(r.amount, r.currency), 0) / 2;
    return { id: 'cobro_hoy', pri: 72, cd: 1, mood: 'celebra', prop: 'confeti', text: V(`¡Hoy es día de cobro! Apenas te caiga, aparta primero lo de los fijos${fix > 0 ? ` (unos ${show(fix)})` : ''} y lo de las deudas. Lo que quede es lo tuyo.`, `¡Hoy es día de cobro! Aparta primero lo de los fijos${fix > 0 ? ` (unos ${show(fix)})` : ''} y lo de las deudas.`) };
  });
  rule((C) => {  // mañana cobras
    const q = (D().incomeSources || []).filter((s) => s.frequency === 'quincenal'), dd = C.R.day;
    if (!q.length || !(dd === 14 || dd === C.R.last - 1)) return null;
    return { id: 'cobro_manana', pri: 50, cd: 1, mood: 'pensando', prop: 'calendario', label: dd === 14 ? '15' : String(C.R.last), text: V('Mañana cobras. Aguanta un poquito y no gastes hoy en nada que pueda esperar.', 'Mañana cobras. Evita hoy los gastos que puedan esperar.') };
  });
  rule((C) => {  // primeros días: ya se fue la mitad
    if (C.inc <= 0 || C.R.day - (C.R.h === 1 ? 1 : 16) > 3) return null;
    const p = C.spent / C.inc;
    if (p < .5) return null;
    return { id: 'arranque_fuerte', pri: 74, cd: 3, mood: 'preocupado', prop: 'lupa', text: V(`En los primeros días ya se fue el ${Math.round(p * 100)} % de la quincena. ¡Epa! Pisa el freno, que todavía falta un trecho para el próximo cobro.`, `En los primeros días ya se fue el ${Math.round(p * 100)} % de la quincena. Conviene frenar un poco.`), btn: ['Ver mis gastos', 'data-tab="movimientos"'] };
  });
  rule((C) => {  // cuánto te toca por día hasta el cobro
    if (C.inc <= 0 || C.free <= 0 || C.R.daysLeft < 3 || C.R.daysLeft > 12) return null;
    const pd = C.free / C.R.daysLeft;
    return { id: 'por_dia', pri: 34, cd: 3, mood: 'pensando', prop: '', text: V(`Para llegar al cobro te tocan unos ${show(pd)} por día (${plural(C.R.daysLeft, 'día', 'días')}). Si hoy gastas menos, mañana tienes más.`, `Para llegar al cobro te tocan unos ${show(pd)} por día durante ${plural(C.R.daysLeft, 'día', 'días')}.`) };
  });
  rule((C) => {  // fin de quincena apretado
    if (C.inc <= 0 || C.R.daysLeft > 4 || C.free < 0 || C.free > C.inc * .12) return null;
    return { id: 'cierre_apretado', pri: 62, cd: 2, mood: 'preocupado', prop: '', text: V(`Quedan ${plural(C.R.daysLeft, 'día', 'días')} para cobrar y te quedan ${show(C.free)}. Toca estirar: solo lo necesario hasta el cobro.`, `Quedan ${plural(C.R.daysLeft, 'día', 'días')} para cobrar y te quedan ${show(C.free)}. Solo lo necesario hasta entonces.`) };
  });
  rule((C) => {  // te sobra al final: apartar para el fondito
    if (C.inc <= 0 || C.R.daysLeft > 5 || C.free < C.inc * .2) return null;
    return { id: 'sobra_fondito', pri: 40, cd: 7, mood: 'celebra', prop: '', text: V(`¡Qué nota! Vas cerrando la quincena con ${show(C.free)}. Si apartas ${show(C.free * .5)} para tu fondito de emergencia, ni lo vas a sentir.`, `Vas cerrando la quincena con ${show(C.free)}. Si apartas ${show(C.free * .5)} para emergencias, ni lo vas a notar.`) };
  });
  rule((C) => {  // cobra semanal
    if (!(D().incomeSources || []).some((s) => s.frequency === 'semanal') || C.R.dow !== 1) return null;
    return { id: 'semanal', pri: 18, cd: 14, mood: 'feliz', prop: '', text: 'Cobras por semana: piensa cada semana como tu mini quincena y reparte lo de los fijos entre las cuatro.' };
  });

  // ───────────────────────── Categorías: comparar con la quincena pasada
  rule((C) => {  // subió una categoría
    let best = null;
    for (const k of Object.keys(CATS)) {
      if (k === 'deudas' || k === 'fijo') continue;
      const a = C.cat[k] || 0, b = C.prevCat[k] || 0;
      if (b >= 5 && a > b * 1.3 && a - b >= 10 && (!best || a - b > best.d)) best = { k, a, d: a - b };
    }
    if (!best) return null;
    return { id: 'sube_' + best.k, pri: 56, cd: 4, mood: 'alerta', prop: 'lupa', text: V(`Esta quincena llevas ${show(best.a)} en ${CATS[best.k]}: ${show(best.d)} más que la quincena pasada. Échale un ojo a qué se fue.`, `Esta quincena llevas ${show(best.a)} en ${CATS[best.k]}: ${show(best.d)} más que la anterior.`), btn: ['Ver mis gastos', 'data-tab="movimientos"'] };
  });
  rule((C) => {  // bajó una categoría
    if (C.R.daysLeft > 3) return null;  // se celebra al final, cuando ya es seguro
    let best = null;
    for (const k of Object.keys(CATS)) {
      if (k === 'deudas' || k === 'fijo') continue;
      const a = C.cat[k] || 0, b = C.prevCat[k] || 0;
      if (b >= 20 && a < b * .7 && (!best || b - a > best.d)) best = { k, d: b - a };
    }
    if (!best) return null;
    return { id: 'baja_' + best.k, pri: 42, cd: 7, mood: 'celebra', prop: 'confeti', text: V(`¡Qué nota! Gastaste ${show(best.d)} menos en ${CATS[best.k]} que la quincena pasada. Así es que se hace.`, `Gastaste ${show(best.d)} menos en ${CATS[best.k]} que la quincena pasada. ¡Bien hecho!`) };
  });
  rule((C) => {  // una categoría se lleva casi todo
    if (C.spent < 30) return null;
    const top = Object.entries(C.cat).filter(([k]) => k !== 'deudas' && k !== 'fijo').sort((a, b) => b[1] - a[1])[0];
    if (!top || top[1] / C.spent < .45) return null;
    return { id: 'top_' + top[0], pri: 30, cd: 7, mood: 'pensando', prop: '', text: `Casi la mitad de lo que gastas esta quincena se va en ${CATS[top[0]] || 'una sola cosa'} (${show(top[1])}). Si recortas un poquito ahí, se nota en todo lo demás.` };
  });
  rule((C) => {  // gastos hormiga
    const small = C.list.filter((e) => e.category === 'hormiga' || (U(e.amount, e.currency) > 0 && U(e.amount, e.currency) < 3 && e.category !== 'deudas'));
    if (small.length < 8) return null;
    const s = sum(small);
    return { id: 'hormiga', pri: 50, cd: 5, mood: 'pensando', prop: 'lupa', text: V(`Llevas ${small.length} gastos chiquitos esta quincena (el cafecito, el pasaje, la chuchería…) que suman ${show(s)}. Uno a uno no se sienten; juntos, sí.`, `Llevas ${small.length} gastos pequeños esta quincena que suman ${show(s)}. Uno a uno no se sienten; juntos, sí.`) };
  });
  rule((C) => {  // delivery
    const l = C.month.filter((e) => has(e.description, ['delivery', 'yummy', 'pedidosya', 'pedidos ya', 'rappi', 'uber eats']));
    if (l.length < 3 || sum(l) < 15) return null;
    return { id: 'delivery', pri: 46, cd: 7, mood: 'pensando', prop: '', text: V(`Este mes llevas ${show(sum(l))} en delivery (${plural(l.length, 'pedido', 'pedidos')}). Cocinar en casa unos días a la semana te ahorra un realero.`, `Este mes llevas ${show(sum(l))} en delivery (${plural(l.length, 'pedido', 'pedidos')}). Cocinar en casa algunos días ahorra bastante.`) };
  });
  rule((C) => {  // taxis y apps de transporte
    const l = C.month.filter((e) => has(e.description, ['taxi', 'ridery', 'yummy rides', 'uber', 'mototaxi', 'indrive', 'cabify']));
    if (l.length < 4 || sum(l) < 15) return null;
    return { id: 'taxis', pri: 38, cd: 7, mood: 'pensando', prop: '', text: V(`Este mes llevas ${show(sum(l))} en taxis y carreritas (${plural(l.length, 'viaje', 'viajes')}). Si alguno lo puedes hacer en camionetica o en metro, ahí hay un ahorro.`, `Este mes llevas ${show(sum(l))} en taxis y apps de transporte (${plural(l.length, 'viaje', 'viajes')}).`) };
  });
  rule((C) => {  // gasolina
    const l = C.month.filter((e) => has(e.description, ['gasolina', 'combustible', 'bomba', 'gasoil']));
    if (l.length < 2) return null;
    return { id: 'gasolina', pri: 22, cd: 14, mood: 'feliz', prop: '', text: V(`Este mes van ${show(sum(l))} en gasolina. Llevar la cuenta te ayuda a saber cuánto apartar cada quincena para la bomba.`, `Este mes van ${show(sum(l))} en combustible. Así sabes cuánto apartar cada quincena.`) };
  });
  rule((C) => {  // fin de semana de salidas
    if (C.R.dow !== 1) return null;
    const sat = key(new Date(Date.now() - 2 * DAY)), sun = key(new Date(Date.now() - DAY));
    const l = (D().expenses || []).filter((e) => e.category === 'ocio' && [sat, sun].includes(dayKey(e.date)));
    if (sum(l) < 15) return null;
    return { id: 'finde', pri: 36, cd: 7, mood: 'pensando', prop: '', text: V(`El fin de semana se fueron ${show(sum(l))} en salidas. ¡Bien que lo disfrutaste! Ahora toca una semanita tranquila para compensar.`, `El fin de semana se fueron ${show(sum(l))} en salidas. Esta semana conviene compensar.`) };
  });
  rule((C) => {  // viernes
    if (C.R.dow !== 5 || C.inc <= 0) return null;
    return { id: 'viernes', pri: 20, cd: 7, mood: 'feliz', prop: '', text: V('¡Llegó el viernes! Si vas a salir, ponte un tope antes de arrancar. Así lo disfrutas sin ratón financiero el lunes.', 'Llegó el viernes. Si vas a salir, ponte un tope antes de empezar.') };
  });
  rule((C) => {  // un gasto grande
    if (C.inc <= 0) return null;
    const big = C.list.filter((e) => e.category !== 'deudas' && e.category !== 'fijo' && !/^rec_/.test(String(e.id))).sort((a, b) => U(b.amount, b.currency) - U(a.amount, a.currency))[0];
    if (!big || U(big.amount, big.currency) < C.inc * .25) return null;
    return { id: 'grande_' + big.id, pri: 44, cd: 30, mood: 'alerta', prop: '', text: `El gasto «${big.description || CATS[big.category] || 'sin nota'}» (${money(big.amount, big.currency)}) se llevó buena parte de la quincena. Si no estaba planificado, ajusta lo demás para que te alcance.` };
  });
  rule((C) => {  // mercado: comida sin presupuesto
    const b = D().budgets || {};
    if ((C.cat.comida || 0) < 40 || b.comida > 0) return null;
    return { id: 'comida_sin_pres', pri: 33, cd: 10, mood: 'pensando', prop: '', text: V(`Llevas ${show(C.cat.comida)} en comida esta quincena y no tiene presupuesto. Ponle un tope y haz la compra con lista: en el mercado es donde más se va el real.`, `Llevas ${show(C.cat.comida)} en comida esta quincena sin presupuesto. Ponle un tope.`), btn: ['Poner presupuesto', 'data-sheet="presupuesto"'] };
  });

  // ───────────────────────── Bolívares y tasas
  rule((C) => {  // bolívares guardados
    if (!ve()) return null;
    let bs = null;
    try { bs = bsBalance(); } catch (_) {}
    if (bs === null || !rate() || bs / rate() < 25) return null;
    return { id: 'bs_guardados', pri: 40, cd: 5, mood: 'pensando', prop: 'cartera', label: 'Bs.', text: `Tienes ${money(bs, 'BS')} en bolívares (unos ${show(bs / rate())}). Planifica en qué los vas a usar pronto: guardados mucho tiempo rinden menos.`, btn: ['¿Pagar en $ o Bs.?', 'data-sheet="caja"'] };
  });
  rule((C) => {  // brecha BCV / USDT
    if (!ve() || !rate() || !mrate()) return null;
    const g = (mrate() / rate() - 1) * 100;
    if (g < 8) return null;
    return { id: 'brecha', pri: 32, cd: 4, mood: 'pensando', prop: '', text: `Hoy el USDT está ${Math.round(g)} % por encima del BCV. Antes de pagar algo en bolívares, compara cuánto te cuesta de verdad en dólares.`, btn: ['Calcular', 'data-sheet="caja"'] };
  });
  rule(() => {  // conseguiste bolívares a mejor tasa (o peor)
    if (!ve()) return null;
    let t = null;
    try { t = tasas(); } catch (_) {}
    if (!t || t.n < 3 || !t.tagged || Math.abs(t.diff) < 3) return null;
    return t.diff > 0
      ? { id: 'tasa_bien', pri: 30, cd: 10, mood: 'celebra', prop: '', text: `Este mes tus compras en bolívares te salieron ${show(t.diff)} más baratas de lo que marca la tasa BCV, porque conseguiste los bolívares a mejor tasa. ¡Pilas así!` }
      : { id: 'tasa_mal', pri: 34, cd: 10, mood: 'alerta', prop: '', text: `Este mes tus compras en bolívares te costaron ${show(-t.diff)} más de lo que marca la tasa BCV. Revisa dónde y a qué tasa consigues tus bolívares.` };
  });
  rule(() => {  // gastos en Bs. sin decir la tasa
    if (!ve() || !mrate()) return null;
    let t = null;
    try { t = tasas(); } catch (_) {}
    if (!t || t.n < 5 || t.tagged) return null;
    return { id: 'tasa_sin_marcar', pri: 22, cd: 14, mood: 'pensando', prop: '', text: 'Cuando anotes un gasto en bolívares, dime a qué tasa los conseguiste (BCV, USDT o tu cambio). Así te digo cuánto te costó de verdad en dólares.' };
  });

  // ───────────────────────── Deudas, tarjetas y Cashea
  rule(() => {  // tarjeta muy usada
    const c = (D().debts || []).filter((x) => x.type === 'tdc' && +x.creditLimit > 0).map((x) => ({ x, p: left(x) / +x.creditLimit })).sort((a, b) => b.p - a.p)[0];
    if (!c || c.p < .7) return null;
    const n = c.x.issuer || c.x.entity || 'tu tarjeta';
    return { id: 'tdc_uso_' + c.x.id, pri: 58, cd: 5, mood: 'alerta', prop: 'cartera', label: n, text: `«${n}» está al ${Math.round(c.p * 100)} % del límite. Úsala menos hasta bajarla: mientras más alta, más intereses pagas.`, btn: ['Ver deudas', 'data-tab="deudas"'] };
  });
  rule(() => {  // pagar la tarjeta antes de la fecha
    const now = key(today()), in5 = key(new Date(Date.now() + 5 * DAY));
    const c = (D().debts || []).filter((x) => x.type === 'tdc' && left(x) > .005 && x.payBefore && x.payBefore >= now && x.payBefore <= in5).sort((a, b) => a.payBefore.localeCompare(b.payBefore))[0];
    if (!c) return null;
    const n = c.issuer || c.entity || 'tu tarjeta';
    return { id: 'tdc_fecha_' + c.id + c.payBefore, pri: 66, cd: 1, mood: 'alerta', prop: 'calendario', label: String(+c.payBefore.slice(8, 10)), text: V(`Paga «${n}» antes del ${+c.payBefore.slice(8, 10)}${c.minPayment ? `: el mínimo es ${money(c.minPayment, c.currency)}` : ''}. Si pagas más del mínimo, mejor: así no te comen los intereses.`, `Paga «${n}» antes del día ${+c.payBefore.slice(8, 10)}${c.minPayment ? `: el mínimo es ${money(c.minPayment, c.currency)}` : ''}.`) };
  });
  rule(() => {  // Cashea
    if (!ve()) return null;
    const l = (D().debts || []).filter((x) => left(x) > .005 && has(`${x.entity} ${x.description} ${x.issuer}`, ['cashea']));
    if (l.length < 2) return null;
    return { id: 'cashea', pri: 52, cd: 7, mood: 'pensando', prop: 'cartera', label: 'Cashea', text: `Tienes ${l.length} compras abiertas en Cashea por ${show(l.reduce((n, x) => n + U(left(x), x.currency), 0))}. Son cómodas, pero las cuotas se van sumando: termina algunas antes de abrir otra.`, btn: ['Ver deudas', 'data-tab="deudas"'] };
  });
  rule(() => {  // muchas deudas
    const l = (D().debts || []).filter((x) => left(x) > .005);
    if (l.length < 4) return null;
    const s = l.slice().sort((a, b) => U(left(a), a.currency) - U(left(b), b.currency))[0];
    return { id: 'muchas_deudas', pri: 44, cd: 7, mood: 'pensando', prop: 'cartera', label: s.entity || '', text: `Tienes ${l.length} deudas abiertas. Una forma de agarrar impulso: termina primero la más pequeña («${s.entity || 'la menor'}», ${money(left(s), s.currency)}) y sigue con la próxima.`, btn: ['Ver mi plan', 'data-tab="deudas"'] };
  });
  rule(() => {  // deudas sin plan
    let c = null, P = null;
    try { c = calc(); P = plan(); } catch (_) {}
    if (!c || c.debt <= .005 || (P && P.configured)) return null;
    return { id: 'sin_plan', pri: 46, cd: 5, mood: 'pensando', prop: '', text: V('Tienes deudas pero todavía no armas tu plan de pagos. En un minuto te digo en cuántos meses sales de eso, pana.', 'Tienes deudas sin plan de pagos. En un minuto te digo en cuántos meses terminas.'), btn: ['Armar mi plan', 'data-tab="deudas"'] };
  });
  rule(() => {  // ya pagaste más de la mitad
    const l = D().debts || [];
    const tot = l.reduce((n, x) => n + U(x.amount, x.currency), 0), paid = l.reduce((n, x) => n + U(x.paid || 0, x.currency), 0);
    if (tot < 20 || paid / tot < .5 || paid >= tot - .005) return null;
    return { id: 'mitad_deudas', pri: 38, cd: 14, mood: 'celebra', prop: 'confeti', text: V(`¡Qué nota! Ya pagaste el ${Math.round(paid / tot * 100)} % de tus deudas. Falta menos de lo que ya hiciste.`, `Ya pagaste el ${Math.round(paid / tot * 100)} % de tus deudas. Falta menos de lo que ya hiciste.`) };
  });
  rule(() => {  // tarjeta sin tasa de interés
    const c = (D().debts || []).find((x) => x.type === 'tdc' && left(x) > .005 && !x.interestKnown);
    if (!c) return null;
    return { id: 'tdc_sin_interes', pri: 24, cd: 21, mood: 'pensando', prop: '', text: `No sé cuánto interés cobra «${c.issuer || c.entity || 'tu tarjeta'}». Si lo anotas (sale en el estado de cuenta), tu plan de pagos sale más exacto.` };
  });

  // ───────────────────────── Me deben
  rule(() => {  // cobro atrasado
    const now = key(today());
    const r = (D().receivables || []).filter((x) => left(x) > .005 && x.dueDate && x.dueDate < now).sort((a, b) => U(left(b), b.currency) - U(left(a), a.currency))[0];
    if (!r) return null;
    const days = Math.round((new Date(now) - new Date(r.dueDate.slice(0, 10))) / DAY);
    return { id: 'me_deben_' + r.id, pri: 54, cd: 3, mood: 'alerta', prop: 'calendario', label: String(+r.dueDate.slice(8, 10)), text: V(`${r.person || 'Alguien'} te debe ${money(left(r), r.currency)} desde hace ${plural(days, 'día', 'días')}. Échale un recordatorio por WhatsApp, con cariño pero sin pena.`, `${r.person || 'Alguien'} te debe ${money(left(r), r.currency)} desde hace ${plural(days, 'día', 'días')}. Mándale un recordatorio.`) };
  });
  rule((C) => {  // te deben mucho en total
    const l = (D().receivables || []).filter((x) => left(x) > .005);
    const s = l.reduce((n, x) => n + U(left(x), x.currency), 0);
    if (!l.length || C.inc <= 0 || s < C.inc * .3) return null;
    return { id: 'me_deben_total', pri: 36, cd: 7, mood: 'pensando', prop: '', text: V(`Entre todos te deben ${show(s)}. Cobrar eso es casi como una quincena extra: no lo dejes enfriar.`, `En total te deben ${show(s)}. Cobrar eso es casi una quincena extra.`) };
  });

  // ───────────────────────── Presupuesto, fijos y suscripciones
  rule((C) => {  // sin presupuesto
    const b = D().budgets || {};
    if (Object.values(b).some((v) => v > 0) || (D().expenses || []).length < 10) return null;
    return { id: 'sin_presupuesto', pri: 42, cd: 7, mood: 'pensando', prop: '', text: 'Ya llevas varios gastos anotados. Ponle un tope a cada cosa (comida, transporte, salidas) y te aviso antes de que te pases.', btn: ['Poner presupuesto', 'data-sheet="presupuesto"'] };
  });
  rule((C) => {  // vas por debajo en todo
    const b = D().budgets || {}, ks = Object.keys(b).filter((k) => b[k] > 0);
    if (ks.length < 2 || C.R.daysLeft > 4 || !ks.every((k) => (C.cat[k] || 0) < b[k] * .5 * .6)) return null;
    return { id: 'bajo_presupuesto', pri: 34, cd: 14, mood: 'celebra', prop: 'confeti', text: V('¡Qué nota! Vas por debajo del presupuesto en todo esta quincena. Lo que te sobre, al fondito.', 'Vas por debajo del presupuesto en todo esta quincena. ¡Muy bien!') };
  });
  rule((C) => {  // fijos muy pesados
    let inc = 0;
    try { inc = calc().inc || 0; } catch (_) {}
    const fx = (D().recurring || []).filter((r) => r.active !== false).reduce((n, r) => n + U(r.amount, r.currency), 0);
    if (inc <= 0 || fx / inc < .45) return null;
    return { id: 'fijos_pesados', pri: 40, cd: 14, mood: 'pensando', prop: '', text: `Tus gastos fijos se comen el ${Math.round(fx / inc * 100)} % de tu sueldo. Revisa si hay alguno que puedas bajar: el plan del teléfono, el internet o alguna suscripción.`, btn: ['Ver fijos', 'data-tab="pagos"'] };
  });
  rule(() => {  // suscripciones
    const words = ['netflix', 'spotify', 'disney', 'hbo', ' max', 'prime', 'youtube', 'apple', 'icloud', 'deezer', 'crunchyroll', 'paramount', 'chatgpt', 'canva'];
    const l = (D().recurring || []).filter((r) => r.active !== false && has(' ' + r.description, words));
    if (l.length < 2) return null;
    return { id: 'suscripciones', pri: 36, cd: 21, mood: 'pensando', prop: 'lupa', text: `Pagas ${l.length} suscripciones (${l.map((r) => r.description).slice(0, 3).join(', ')}) por ${show(l.reduce((n, r) => n + U(r.amount, r.currency), 0))} al mes. ¿Las usas todas? Pausar una ya es un ahorro.` };
  });

  // ───────────────────────── Ingresos
  rule((C) => {  // ingreso extra del mes
    const l = (D().incomes || []).filter((x) => x.kind === 'extra' && inR(x, C.R.month));
    const s = l.reduce((n, x) => n + U(x.amount, x.currency), 0);
    if (s < 10) return null;
    return { id: 'extra_mes', pri: 32, cd: 10, mood: 'feliz', prop: '', text: V(`Este mes te entraron ${show(s)} extra. Aparta una parte antes de que se te diluya en el día a día.`, `Este mes recibiste ${show(s)} extra. Aparta una parte antes de que se diluya.`) };
  });
  rule(() => {  // sin ingresos registrados
    if ((D().incomeSources || []).length || (D().expenses || []).length < 5) return null;
    return { id: 'sin_ingreso', pri: 48, cd: 5, mood: 'pensando', prop: '', text: 'Ya anotas tus gastos, pero no sé cuánto cobras. Agrega tu ingreso y te digo cuánto te queda de verdad en la quincena.', btn: ['Agregar ingreso', 'data-sheet="ingreso"'] };
  });

  // ───────────────────────── Formas de pago y cuentas
  rule((C) => {  // casi todo en efectivo
    if (C.month.length < 10) return null;
    const ef = C.month.filter((e) => e.method === 'efectivo').length;
    if (ef / C.month.length < .7) return null;
    return { id: 'efectivo', pri: 24, cd: 21, mood: 'pensando', prop: '', text: 'Casi todo lo pagas en efectivo. Anota apenas pagues: el efectivo es el que más se escapa sin darnos cuenta.' };
  });
  rule((C) => {  // muchos pagos móviles
    if (!ve()) return null;
    const n = C.month.filter((e) => e.method === 'pago_movil').length;
    if (n < 15) return null;
    return { id: 'pago_movil', pri: 20, cd: 21, mood: 'pensando', prop: '', text: `Llevas ${n} pagos móviles este mes. Antes de confirmar, revisa teléfono, cédula y banco, y guarda la referencia por si hay que reclamar.` };
  });
  rule((C) => {  // Zelle o Binance sin cuenta propia
    const used = C.month.some((e) => e.method === 'zelle' || e.method === 'binance');
    let acc = [];
    try { acc = accounts(); } catch (_) {}
    if (!used || acc.some((a) => a.kind === 'digital' || a.kind === 'cripto')) return null;
    return { id: 'zelle_cuenta', pri: 26, cd: 21, mood: 'pensando', prop: '', text: 'Pagas con Zelle o Binance, pero no tienes esas cuentas registradas. Agrégalas y sabrás cuánto tienes en cada lado.', btn: ['Agregar cuenta', 'data-sheet="cuenta"'] };
  });

  // ───────────────────────── Hábitos
  rule(() => {  // varios días sin anotar
    const l = D().expenses || [];
    if (l.length < 5) return null;
    const last = l.map((e) => dayKey(e.date)).sort().pop();
    const days = Math.round((new Date(key(today())) - new Date(last)) / DAY);
    if (days < 3) return null;
    return { id: 'sin_anotar', pri: 47, cd: 2, mood: 'pensando', prop: '', text: V(`Hace ${plural(days, 'día', 'días')} que no anotas nada. ¿De verdad no gastaste o se te pasó? Ponte al día, que así es que cuadran las cuentas.`, `Hace ${plural(days, 'día', 'días')} que no anotas nada. ¿Se te pasó? Ponte al día.`), btn: ['Anotar gasto', 'data-sheet="gasto"'] };
  });

  // ───────────────────────── Temporadas del año (Venezuela)
  rule((C) => {  // aguinaldos y diciembre
    if (!ve() || !(C.R.m === 10 || C.R.m === 11)) return null;
    return { id: 'diciembre', pri: 40, cd: 10, mood: 'feliz', prop: 'calendario', label: 'Dic', text: 'Se vienen las hallacas, los estrenos y los regalos. Si te pagan aguinaldos o utilidades, separa una parte para enero, que es el mes más largo del año.' };
  });
  rule((C) => {  // enero
    if (!ve() || C.R.m !== 0 || C.R.day > 20) return null;
    return { id: 'enero', pri: 40, cd: 10, mood: 'pensando', prop: '', text: 'Enero es largo y la primera quincena tarda en llegar. Cuida lo que quedó de diciembre y no te metas en cuotas nuevas este mes.' };
  });
  rule((C) => {  // inicio de clases
    if (!ve() || !(C.R.m === 7 || (C.R.m === 8 && C.R.day <= 20))) return null;
    if (!(D().schoolExpenses || []).length && !(D().expenses || []).some((e) => e.category === 'educacion')) return null;
    return { id: 'clases', pri: 44, cd: 10, mood: 'pensando', prop: 'calendario', label: 'Sep', text: 'Se acerca el inicio de clases: útiles, uniformes y la inscripción. Anótalos en Pagos como compromisos y ve apartando un poquito cada quincena.', btn: ['Anotar compromiso', 'data-sheet="compromiso"'] };
  });
  rule((C) => {  // carnaval / Semana Santa (febrero a abril): viajes
    if (!ve() || !(C.R.m >= 1 && C.R.m <= 3) || C.R.day > 10) return null;
    return { id: 'feriados', pri: 18, cd: 30, mood: 'feliz', prop: '', text: 'Si piensas viajar en Carnaval o Semana Santa, empieza a apartar desde ya: pasajes, posada y comida suben en esas fechas.' };
  });
  rule((C) => {  // Día de las Madres
    if (!ve() || C.R.m !== 4 || C.R.day > 10) return null;
    return { id: 'madres', pri: 22, cd: 30, mood: 'feliz', prop: '', text: 'El segundo domingo de mayo es el Día de las Madres. Planifica el detalle con tiempo y no a última hora, que es cuando sale más caro.' };
  });

  // Elige: las que aplican, sin las que están descansando, de la más importante a la menos.
  const SEEN = 'qrseen';
  const seen = () => { try { return JSON.parse(localStorage.getItem(SEEN) || '{}') || {}; } catch (_) { return {}; } };
  function markSeen(id) {
    try { const m = seen(); m[id] = key(today()); const ks = Object.keys(m); if (ks.length > 120) for (const k of ks.slice(0, ks.length - 120)) delete m[k]; localStorage.setItem(SEEN, JSON.stringify(m)); } catch (_) {}
  }
  function candidates() {
    if (!S.data) return [];
    const R = ranges(), list = exps(R.cur), month = exps(R.month), inc = quincenaIncome(R), spent = sum(list);
    let due = 0;
    try { const cp = calcP(), p = per(); if (p.y === today().getFullYear() && p.m === R.m && p.h === R.h) due = Math.max(0, (cp.inc - spent) - cp.free); } catch (_) {}
    const C = { R, list, month, inc, spent, free: inc - spent - due, cat: byCat(list), prevCat: byCat(exps(R.prev)) };
    const m = seen(), t = key(today()), out = [];
    for (const f of rules) {
      let r = null;
      try { r = f(C); } catch (_) { r = null; }
      if (!r) continue;
      const last = m[r.id];
      if (last && last !== t && (new Date(t) - new Date(last)) / DAY < (r.cd || 3)) continue;
      out.push(r);
    }
    return out.sort((a, b) => b.pri - a.pri);
  }
  return { candidates, markSeen, count: () => rules.length };
})();

// La observación personalizada número «extra» (0 = la más importante), o null si ya no quedan.
function quinciRule(extra = 0) {
  const c = QR.candidates();
  if (extra >= c.length) return null;
  const r = c[extra];
  QR.markSeen(r.id);
  return { mood: r.mood, prop: r.prop || '', label: r.label || '', text: r.text, btn: r.btn || ['Otro consejo', 'data-qtip="1"'], more: true, ruleId: r.id };
}
