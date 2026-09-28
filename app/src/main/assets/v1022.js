/* ETS Enerji Takip v1.0.23 final UI and Android integration */
(function(){
  'use strict';
  const VERSION='1.0.23';
  const DEFAULT_IND=20;
  const DEFAULT_CAP=15;
  const oldSchema=Number(APP.settings.schemaVersion||0);
  if(oldSchema<22){
    if(!Number.isFinite(Number(APP.settings.indLimit))||Number(APP.settings.indLimit)===15)APP.settings.indLimit=DEFAULT_IND;
    if(!Number.isFinite(Number(APP.settings.capLimit))||Number(APP.settings.capLimit)===10)APP.settings.capLimit=DEFAULT_CAP;
    APP.settings.monthlyInd=APP.settings.indLimit;
    APP.settings.monthlyCap=APP.settings.capLimit;
    APP.settings.forecastWarningRatio=Math.max(50,Math.min(99,Number(APP.settings.forecastWarningRatio)||80));
    APP.settings.schemaVersion=22;
    saveSettings();
  }
  APP.companyStatusFilter='all';

  function finiteValue(v){return v!==null&&v!==undefined&&String(v).trim()!==''&&Number.isFinite(Number(v))}
  function warningFactor(){return Math.max(.5,Math.min(.99,(Number(APP.settings.forecastWarningRatio)||80)/100))}
  function stateFor(ind,cap){
    const values=[ind,cap].filter(finiteValue);
    if(!values.length)return 'unknown';
    if((finiteValue(ind)&&Number(ind)>=Number(APP.settings.indLimit))||(finiteValue(cap)&&Number(cap)>=Number(APP.settings.capLimit)))return 'critical';
    if((finiteValue(ind)&&Number(ind)>=Number(APP.settings.indLimit)*warningFactor())||(finiteValue(cap)&&Number(cap)>=Number(APP.settings.capLimit)*warningFactor()))return 'warning';
    return 'normal';
  }
  function stateText(state){return state==='critical'?'Kritik':state==='warning'?'Uyarı':state==='normal'?'Normal':'Veri Yok'}
  function stateColor(value,limit){if(!finiteValue(value))return '#a5abb3';if(Number(value)>=Number(limit))return 'var(--critical)';if(Number(value)>=Number(limit)*warningFactor())return 'var(--warning)';return 'var(--normal)'}
  function progress(value,limit){return finiteValue(value)?Math.max(0,Math.min(100,Number(value)/(Number(limit)||1)*100)):0}
  function companyMiniRatio(label,value,limit){return `<div class="company-mini-ratio"><div class="company-mini-circle" style="--mini-p:${progress(value,limit)};--mini-color:${stateColor(value,limit)}"><span>${esc(label)}</span></div><b>${finiteValue(value)?'%'+fmt(Number(value),2):'—'}</b></div>`}
  function companyStatus(c){return stateFor(c.monthlyInd,c.monthlyCap)}
  function navButton(id,label,iconName,handler,active){return `<button class="${active===id?'active':''}" onclick="${handler}">${icon(iconName)}<span>${label}</span></button>`}
  bottom=function(active='overview'){if(active==='reports')active=APP.report?'companies':'overview';return `<nav class="bottom-nav" aria-label="Alt menü">${navButton('overview','Genel Bakış','home','showDashboard()',active)}${navButton('companies','Şirketler','companies','showCompanies()',active)}${navButton('alerts','Uyarılar','alert','showAlerts()',active)}${navButton('settings','Ayarlar','settings','showSettings()',active)}</nav>`};

  loading=function(label='Yükleniyor...'){
    app.innerHTML=`<main class="loading-screen"><section class="loading-panel"><img src="ets_logo.png" class="ets-logo" alt="ETS"><h2>ETS Enerji Takip</h2><div class="sub">${esc(label)}</div><div class="loading-track"><i></i></div><div class="loading-dots"><i></i><i></i><i></i></div></section></main>`;
  };

  companyRows=function(list){
    if(!list.length)return '<div class="empty">Arama veya filtreye uygun şirket bulunamadı.</div>';
    const visible=list.slice(0,APP.companyLimit);
    const rows=visible.map(c=>{const state=companyStatus(c);return `<section class="card company company-card v1022" onclick="openCompany('${encodeURIComponent(c.id)}')"><span class="company-avatar">${esc(initials(c.name))}</span><div class="company-copy"><div class="strong">${esc(c.name)}</div><div class="company-meta"><span>Firma ID: ${esc(c.id)}</span><span class="company-status ${state}">${stateText(state)}</span></div>${c.monthlyPeriod?`<div class="company-period">${esc(c.monthlyPeriod)}</div>`:''}</div><div class="company-mini-ratios">${companyMiniRatio('İND',c.monthlyInd,APP.settings.indLimit)}${companyMiniRatio('KAP',c.monthlyCap,APP.settings.capLimit)}</div><span class="chev">${icon('chevron')}</span></section>`}).join('');
    return rows+(visible.length<list.length?`<button class="btn btn-soft btn-block load-more" onclick="loadMoreCompanies()">Daha Fazla Göster (${list.length-visible.length})</button>`:'');
  };
  filterCompanies=function(q){
    const n=norm(q||''),filter=APP.companyStatusFilter||'all';APP.companyLimit=60;
    APP.filtered=APP.companies.filter(c=>(!n||norm(c.name).includes(n)||String(c.id).includes(String(q||'')))&&(filter==='all'||companyStatus(c)===filter));
    const list=$('#companyList');if(list)list.innerHTML=companyRows(APP.filtered);
    const count=$('#companyCount');if(count)count.textContent=`${APP.filtered.length} şirket`;
  };
  window.setCompanyStatusFilter=function(value){APP.companyStatusFilter=value||'all';filterCompanies($('#search')?.value||'')};
  monthOptions=function(m,y){let s='';const anchor=new Date();anchor.setDate(1);for(let k=0;k<48;k++){const d=new Date(anchor.getFullYear(),anchor.getMonth()-k,1),mm=d.getMonth()+1,yy=d.getFullYear();s+=`<option ${mm===Number(m)&&yy===Number(y)?'selected':''} value="${mm}-${yy}">${MONTHS[mm-1]} ${yy}</option>`}if(!s.includes(' selected'))s=`<option selected value="${m}-${y}">${MONTHS[Number(m)-1]} ${y}</option>`+s;return s};
  showCompanies=function(){
    APP.report=null;APP.filtered=[...APP.companies];APP.companyLimit=60;APP.companyStatusFilter='all';
    app.innerHTML=`${topBar(APP.role==='admin'?'Tüm Şirketler':'Şirketlerim',false)}${offlineBar()}<main class="page"><div class="company-filter-row"><div class="search-shell">${icon('search')}<input class="input" id="search" placeholder="Şirket veya firma ID ara..." oninput="filterCompanies(this.value)"><button class="btn btn-soft" aria-label="Yenile" onclick="refreshCompanies()">${icon('refresh')}</button></div><select class="filter-select" onchange="setCompanyStatusFilter(this.value)"><option value="all">Tüm Durumlar</option><option value="normal">Normal</option><option value="warning">Uyarı</option><option value="critical">Kritik</option><option value="unknown">Veri Yok</option></select></div><div class="list-meta"><div><b id="companyCount">${APP.filtered.length} şirket</b><div class="sub">Daireler aylık İND ve KAP yüzdelerini gösterir</div></div><span class="pill redp">Canlı Liste</span></div><div id="companyList">${companyRows(APP.filtered)}</div></main>${bottom('companies')}`;
  };

  function averageCompanyRatio(key){const vals=APP.companies.map(c=>c[key]).filter(finiteValue).map(Number);return vals.length?vals.reduce((a,b)=>a+b,0)/vals.length:0}
  function dashboardRatioRing(label,value,limit){const safe=finiteValue(value)?Number(value):0;return `<article class="ratio-ring-card"><span class="sub">${esc(label)}</span><div class="ratio-ring" style="--ring-p:${progress(safe,limit)};--ring-color:${stateColor(safe,limit)}"><div class="ratio-ring-inner"><b>%${fmt(safe,2)}</b></div></div><div class="limit-note">Limit: %${fmt(limit,1)}</div></article>`}
  function instantMetric(label,value,unit,note,digits=2){return `<article class="electrical-metric"><span>${esc(label)}</span><b>${finiteValue(value)?fmt(Number(value),digits):'—'} <small>${esc(unit)}</small></b><em>${esc(note||'')}</em></article>`}
  function instantPhaseCells(values,digits=2){const list=Array.isArray(values)?values:[];return [0,1,2].map(i=>`<td>${finiteValue(list[i])?fmt(Number(list[i]),digits):'—'}</td>`).join('')}
  showDashboard=function(){
    if(!APP.logged&&APP.companies.length===0)return showLogin();APP.report=null;
    const counts={normal:0,warning:0,critical:0,unknown:0};APP.companies.forEach(c=>counts[companyStatus(c)]++);
    const last=localStorage.getItem('etm_last_scan')||'Henüz kontrol edilmedi';
    const avgInd=averageCompanyRatio('monthlyInd'),avgCap=averageCompanyRatio('monthlyCap');
    app.innerHTML=`${topBar('Genel Bakış',false)}${offlineBar()}<main class="page"><section class="card dashboard-hero"><div class="sub">ETS Energy Tracking App</div><h2 style="margin:5px 0 12px">Enerji durumları ve aylık reaktif oranlar</h2><div class="sub">Son senkronizasyon: ${esc(last)}</div><button class="btn btn-sm" style="margin-top:15px" onclick="scanAlerts(true)">${icon('refresh')} Şimdi Senkronize Et</button></section><div class="dashboard-status-grid"><article><span>Toplam Şirket</span><b>${APP.companies.length}</b></article><article class="normal"><span>Normal</span><b>${counts.normal}</b></article><article class="warning"><span>Yaklaşan Limit</span><b>${counts.warning}</b></article><article class="critical"><span>Kritik</span><b>${counts.critical}</b></article></div><div class="section-heading"><div><strong>Aylık Ortalama Oranlar</strong><span>Tüm izinli şirketler</span></div></div><div class="dashboard-ratio-summary">${dashboardRatioRing('Aylık İndüktif',avgInd,APP.settings.indLimit)}${dashboardRatioRing('Aylık Kapasitif',avgCap,APP.settings.capLimit)}</div><div class="section-heading"><div><strong>Hızlı İşlemler</strong><span>Bir işlem seçin</span></div></div><div class="quick-grid"><section class="card quick-action" onclick="showCompanies()">${icon('companies')}<b>Şirketleri görüntüle</b></section><section class="card quick-action" onclick="showAlerts()">${icon('alert')}<b>Telefon uyarıları</b></section><section class="card quick-action" onclick="showMonthlyForecast()">${icon('reports')}<b>Aylık limit tahmini</b></section><section class="card quick-action" onclick="showSettings()">${icon('settings')}<b>Limit ve sunucu ayarları</b></section></div></main>${bottom('overview')}`;
  };

  function valueOrDash(v,d=3,unit=''){return finiteValue(v)?fmt(Number(v),d)+(unit?' '+unit:''):'—'}
  function lastReadingDateTime(r){
    const values=[r?.lastRead,r?.companyDetails?.lastData,r?.company?.details?.lastData,r?.records?.at(-1)?.date];
    for(const value of values){
      const clean=typeof window.readingDateTimeOnly==='function'?window.readingDateTimeOnly(value):'';
      if(clean)return clean;
    }
    return '—';
  }
  currentTab=function(r){
    const c=r.current||{},has=(Array.isArray(c.a)&&c.a.some(finiteValue))||(Array.isArray(c.v)&&c.v.some(finiteValue));
    const energy=`<div class="overview-energy-grid"><article><span>Toplam Enerji</span><b>${valueOrDash(c.kwh,3,'kWh')}</b></article><article><span>İndüktif Enerji</span><b>${valueOrDash(c.indKvarh,3,'kvarh')}</b></article><article><span>Kapasitif Enerji</span><b>${valueOrDash(c.capKvarh,3,'kvarh')}</b></article></div>`;
    if(!has)return `${energy}<div class="electrical-empty"><b>Akım / gerilim verisi henüz okunamadı.</b><div class="sub" style="margin-top:7px">Son ETS raporunda L1–L3 değerleri aranacaktır.</div></div><button class="btn btn-primary btn-block" onclick="reloadCurrent()">${icon('refresh')} Yeniden Oku</button>`;
    const totalActive=finiteValue(c.totalActive)?Number(c.totalActive):(Array.isArray(c.active)&&c.active.filter(Number.isFinite).length?c.active.filter(Number.isFinite).reduce((a,b)=>a+b,0):r.activePower);
    return `<div class="electrical-grid">${instantMetric('Ort. Gerilim',r.avgV,'V','L1–L3 ortalaması')}${instantMetric('Ort. Akım',r.avgA,'A','L1–L3 ortalaması')}${instantMetric('Aktif Güç',totalActive,'kW',c.activeEstimated?'Gerilim ve akımdan hesaplandı':'Sunucudan okundu',3)}</div><article class="current-card"><table class="phase-table"><thead><tr><th></th><th>L1</th><th>L2</th><th>L3</th></tr></thead><tbody><tr class="phase-accent"><td>Gerilim (V)</td>${instantPhaseCells(c.v,2)}</tr><tr class="phase-accent"><td>Akım (A)</td>${instantPhaseCells(c.a,2)}</tr><tr><td>Aktif Güç (kW)</td>${instantPhaseCells(c.active,3)}</tr></tbody></table></article>${energy}${warningHtml(r)}<button class="btn btn-soft btn-block" onclick="reloadCurrent()">${icon('refresh')} Değerleri Yenile</button>`;
  };
  dailyTab=function(r){
    return `<div class="monthly-note"><b>${MONTHS[r.month-1]} ${r.year} şirket özeti</b><br>Son okuma, toplam enerjiler ve aylık reaktif yüzdeler.</div><div class="overview-energy-grid"><article><span>Toplam kWh</span><b>${valueOrDash(r.current?.kwh,3,'kWh')}</b></article><article><span>İndüktif kvarh</span><b>${valueOrDash(r.current?.indKvarh,3,'kvarh')}</b></article><article><span>Kapasitif kvarh</span><b>${valueOrDash(r.current?.capKvarh,3,'kvarh')}</b></article></div>${monthlyRings(r)}${warningHtml(r)}<div class="small-actions export-actions"><button class="btn btn-soft btn-sm" onclick="showTableReport()">Aylık Tablo</button><button class="btn btn-soft btn-sm" onclick="exportExcel()">${icon('excel')} Excel</button><button class="btn btn-soft btn-sm" onclick="exportPdf()">${icon('pdf')} PDF</button></div>`;
  };
  reactiveTab=function(r){const state=stateFor(r.indRatio,r.capRatio);return `<div class="monthly-note"><b>Aylık reaktif görünüm</b><br>Durum: <span class="company-status ${state}">${stateText(state)}</span></div>${monthlyRings(r)}<div class="overview-energy-grid"><article><span>Toplam kWh</span><b>${valueOrDash(r.current?.kwh,3,'kWh')}</b></article><article><span>İndüktif kvarh</span><b>${valueOrDash(r.current?.indKvarh,3,'kvarh')}</b></article><article><span>Kapasitif kvarh</span><b>${valueOrDash(r.current?.capKvarh,3,'kvarh')}</b></article></div>${warningHtml(r)}<div class="small-actions export-actions"><button class="btn btn-soft btn-sm" onclick="exportExcel()">${icon('excel')} Excel</button><button class="btn btn-soft btn-sm" onclick="exportPdf()">${icon('pdf')} PDF</button></div>`};
  showDevice=function(tab=APP.tab){
    if(!APP.report)return showCompanies();APP.tab=tab;const r=APP.report,c=r.company,state=stateFor(r.indRatio,r.capRatio);
    const title=tab==='daily'?'Şirket Genel Bakış':tab==='current'?'Anlık Elektrik Değerleri':'Reaktif Enerji';
    app.innerHTML=`${topBar(title)}${offlineBar()}<main class="page"><section class="device-card"><div class="device-company-row"><span class="company-avatar">${esc(initials(c.name))}</span><div><div class="device-title">${esc(c.name)}</div><div class="company-meta"><span class="company-status ${state}">${stateText(state)}</span><span>Firma ID ${esc(c.id)}</span></div></div></div><div class="meta-grid"><div><span class="meta-label">Sayaç / Modem IMEI</span><strong class="meta-value">${esc(r.imei||'—')}</strong></div><div><span class="meta-label">Son Okuma Zamanı</span><strong class="meta-value">${esc(lastReadingDateTime(r))}</strong></div><div><span class="meta-label">Toplam Enerji</span><strong class="meta-value">${valueOrDash(r.current?.kwh,3,'kWh')}</strong></div><div><span class="meta-label">Rapor Dönemi</span><strong class="meta-value">${MONTHS[r.month-1]} ${r.year}</strong></div></div></section><section class="thin-info"><span>İndüktif Limit: <b>%${APP.settings.indLimit}</b></span><span>Kapasitif Limit: <b>%${APP.settings.capLimit}</b></span></section><nav class="tabs" aria-label="Rapor tipi"><button class="tab ${tab==='daily'?'active':''}" onclick="showDevice('daily')">Genel Bakış</button><button class="tab ${tab==='current'?'active':''}" onclick="showDevice('current')">Anlık Değerler</button><button class="tab ${tab==='reactive'?'active':''}" onclick="showDevice('reactive')">Reaktif</button></nav><section class="monthbar"><button class="btn btn-sm" onclick="changeMonth(-1)">‹ Önceki</button><div class="monthpick">${MONTHS[r.month-1]} ${r.year}</div><button class="btn btn-sm" onclick="changeMonth(1)">Sonraki ›</button></section>${deviceTab(r,tab)}</main>${bottom('companies')}`;
  };

  function phoneNotify(company,item,r){
    if(!APP.settings.notifications||!(window.AndroidHttp&&typeof window.AndroidHttp.showAlertNotification==='function'))return;
    const key=`${company.id}_${item.type}_${r.month}_${r.year}`;
    const signature=`${item.status}_${Math.floor(item.value)}`;
    if(localStorage.getItem('etm_phone_notice_'+key)===signature)return;
    localStorage.setItem('etm_phone_notice_'+key,signature);
    const title=item.status==='critical'?'ETS Kritik Limit Uyarısı':'ETS Yaklaşan Limit Uyarısı';
    const reading=typeof window.readingDateTimeOnly==='function'?(window.readingDateTimeOnly(r.lastRead||r.records?.at(-1)?.date)||'—'):(r.lastRead||'—');
    const message=`${company.name}: ${item.type} %${fmt(item.value)} / limit %${item.limit} • Son okuma: ${reading}`;
    window.AndroidHttp.showAlertNotification(title,message,Math.abs([...key].reduce((a,ch)=>((a<<5)-a)+ch.charCodeAt(0),0)),String(company.id));
  }
  checkReportAlert=function(company,r,popup=false){
    const candidates=[{type:'İndüktif',value:Number(r.indRatio),limit:Number(APP.settings.indLimit)},{type:'Kapasitif',value:Number(r.capRatio),limit:Number(APP.settings.capLimit)}];
    const active=candidates.filter(x=>Number.isFinite(x.value)&&x.value>=x.limit*warningFactor()).map(x=>({...x,status:x.value>=x.limit?'critical':'warning'}));
    const activeKeys=new Set();
    active.forEach(x=>{const key=`${company.id}_${x.type}_${r.month}_${r.year}`;activeKeys.add(key);let a=APP.alerts.find(z=>z.key===key);if(!a){a={key,companyId:company.id,company:company.name,type:x.type,value:x.value,limit:x.limit,time:new Date().toISOString(),active:true,month:r.month,year:r.year,status:x.status};APP.alerts.unshift(a)}else{a.value=x.value;a.limit=x.limit;a.active=true;a.status=x.status;a.time=new Date().toISOString()}phoneNotify(company,x,r)});
    APP.alerts.filter(a=>String(a.companyId)===String(company.id)&&Number(a.month)===Number(r.month)&&Number(a.year)===Number(r.year)).forEach(a=>{if(!activeKeys.has(a.key))a.active=false});
    saveAlerts();
    if(popup&&active.length&&APP.settings.notifications){modal(active.some(x=>x.status==='critical')?'Kritik Reaktif Limit Uyarısı':'Reaktif Limit Yaklaşıyor',`<b>${esc(company.name)}</b><br>${active.map(x=>`${esc(x.type)}: <b class="${x.status==='critical'?'red':'orange'}">%${fmt(x.value)}</b> / limit %${x.limit}`).join('<br>')}`,`<button class="btn btn-primary btn-block" onclick="closeModal();sendWhatsAppCurrent()">WhatsApp Mesajı Hazırla</button><button class="btn btn-soft btn-block" style="margin-top:8px" onclick="closeModal()">Tamam</button>`)}
  };
  alertHtml=function(a){const state=a.status||(a.value>=a.limit?'critical':'warning');return `<section class="card alert-item"><span class="alert-icon ${state==='critical'?'danger':''}">⚠</span><div><div class="strong">${esc(a.company)}</div><div class="sub">${state==='critical'?'Limit aşıldı':'Limite yaklaşıyor'} • ${esc(a.type)}</div><div class="sub">${new Date(a.time).toLocaleString('tr-TR')}</div></div><div class="center"><div class="alert-value ${state==='critical'?'red':'orange'}">%${fmt(a.value)}</div><button class="btn btn-soft btn-sm" onclick="sendWhatsAppAlert('${encodeURIComponent(a.key)}')">WhatsApp</button></div></section>`};
  showAlerts=function(){const active=APP.alerts.filter(a=>a.active),past=APP.alerts.filter(a=>!a.active);app.innerHTML=`${topBar('Uyarılar',false)}<main class="page"><div class="row between"><div><h1 class="section-title" style="margin-bottom:2px">Aktif Uyarılar</h1><div class="sub">İndüktif %${APP.settings.indLimit} • Kapasitif %${APP.settings.capLimit}</div></div><button class="btn btn-soft btn-sm" onclick="scanAlerts(true)">↻ Kontrol Et</button></div><div class="phone-alert-note">${icon('alert')}<div>Uyarı veya kritik durum bulunduğunda Android telefon bildirimi gösterilir. WhatsApp mesajı yalnızca manuel gönderim için hazırlanır.</div></div>${active.length?`<div class="small-actions"><button class="btn btn-primary btn-block" onclick="sendWhatsAppAllAlerts()">Tüm Uyarıları WhatsApp'a Hazırla</button></div>${active.map(alertHtml).join('')}`:'<div class="empty">Aktif uyarı bulunmuyor.</div>'}${past.length?`<h2 class="section-title">Geçmiş Uyarılar</h2>${past.slice(0,20).map(alertHtml).join('')}`:''}</main>${bottom('alerts')}`};

  window.clearAppCache=function(){
    [...Array(localStorage.length)].map((_,i)=>localStorage.key(i)).filter(k=>k&&(/^(etm_report_|etm_monthly_|etm_forecast_|etm_phone_notice_)/.test(k))).forEach(k=>localStorage.removeItem(k));
    APP.forecast=[];APP.monthly=[];
    if(window.AndroidHttp&&typeof window.AndroidHttp.clearNativeCache==='function')window.AndroidHttp.clearNativeCache();
    toast('Rapor ve uygulama önbelleği temizlendi.');
  };
  showSettings=function(){app.innerHTML=`${topBar('Ayarlar',false)}<main class="page"><h1 class="section-title">Reaktif Limitler</h1><section class="card"><div class="setting-row"><label>İndüktif Limit (%)</label><input id="indLimit" class="input" type="number" min="0.1" step="0.1" value="${APP.settings.indLimit}"><div class="settings-hint">Varsayılan: %20</div></div><div class="setting-row"><label>Kapasitif Limit (%)</label><input id="capLimit" class="input" type="number" min="0.1" step="0.1" value="${APP.settings.capLimit}"><div class="settings-hint">Varsayılan: %15</div></div><div class="setting-row"><label>Yaklaşan Limit Seviyesi (%)</label><input id="forecastWarningRatio" class="input" type="number" min="50" max="99" step="1" value="${APP.settings.forecastWarningRatio||80}"><div class="settings-hint">Limitin bu yüzdesine ulaşıldığında uyarı oluşturulur.</div></div></section><h2 class="section-title">Telefon Uyarıları</h2><section class="card"><div class="row between setting-row"><div><b>Android Bildirimleri</b><div class="sub">Uyarı ve kritik durumları telefonda göster</div></div><label class="switch"><input id="notifications" type="checkbox" ${APP.settings.notifications?'checked':''} onchange="this.checked&&window.AndroidHttp&&AndroidHttp.requestNotificationPermission&&AndroidHttp.requestNotificationPermission()"><span></span></label></div><div class="setting-row"><label>Kontrol Aralığı (dakika)</label><input id="interval" class="input" type="number" min="5" value="${APP.settings.interval||15}"></div></section><h2 class="section-title">WhatsApp</h2><section class="card"><div class="setting-row"><label>WhatsApp Numarası</label><input id="phone" class="input" inputmode="tel" value="${esc(APP.settings.phone||'')}" placeholder="+90 555 123 45 67"></div><div class="row between"><b>Mesaj Hazırlama</b><label class="switch"><input id="whatsapp" type="checkbox" ${APP.settings.whatsapp?'checked':''}><span></span></label></div></section><h2 class="section-title">Sunucu</h2><section class="card"><label>Server Address</label><input id="baseUrl" class="input" value="${esc(APP.base)}"></section><button class="btn btn-primary btn-block" onclick="saveSettingsForm()">Ayarları Kaydet</button><div class="settings-actions"><button class="btn btn-danger" onclick="clearAppCache()">Önbelleği Temizle</button><button class="btn btn-secondary" onclick="logout()">Çıkış Yap</button></div></main>${bottom('settings')}`};
  saveSettingsForm=function(){const n=id=>Number($('#'+id)?.value);APP.settings.indLimit=n('indLimit')||DEFAULT_IND;APP.settings.capLimit=n('capLimit')||DEFAULT_CAP;APP.settings.monthlyInd=APP.settings.indLimit;APP.settings.monthlyCap=APP.settings.capLimit;APP.settings.forecastWarningRatio=Math.max(50,Math.min(99,n('forecastWarningRatio')||80));APP.settings.interval=Math.max(5,n('interval')||15);APP.settings.phone=$('#phone')?.value.trim()||'';APP.settings.notifications=!!$('#notifications')?.checked;APP.settings.whatsapp=!!$('#whatsapp')?.checked;APP.settings.schemaVersion=22;APP.base=$('#baseUrl')?.value.trim().replace(/\/$/,'')||APP.base;saveSettings();startMonitor();toast('Ayarlar kaydedildi.');showSettings()};

  function blobToBase64(blob){return new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(',')[1]||'');reader.onerror=reject;reader.readAsDataURL(blob)})}
  async function presentPdf(name,blob){APP.pendingPdf={name,blob};modal('PDF Raporu Hazır',`Rapor otomatik olarak indirilmez. Açmak veya cihazınıza kaydetmek için aşağıdaki seçeneklerden birini kullanın.<div class="pdf-path-note">İndirilen dosyalar: Downloads/ETS Enerji Takip</div>`,`<div class="pdf-choice-actions"><button class="btn btn-soft" onclick="openPreparedPdf('open')">PDF'yi Aç</button><button class="btn btn-primary" onclick="openPreparedPdf('download')">PDF'yi İndir</button></div><button class="btn btn-secondary btn-block" style="margin-top:9px" onclick="closeModal()">İptal</button>`)}
  window.openPreparedPdf=async function(action){const item=APP.pendingPdf;if(!item)return;closeModal();toast(action==='open'?'PDF açılıyor...':'PDF indiriliyor...');try{if(window.AndroidHttp&&typeof window.AndroidHttp.handlePdf==='function'){const base64=await blobToBase64(item.blob);window.AndroidHttp.handlePdf(item.name,base64,action);return}if(action==='download')return deliverFile(item.name,'application/pdf',item.blob);const url=URL.createObjectURL(item.blob);window.open(url,'_blank');setTimeout(()=>URL.revokeObjectURL(url),60000)}catch(e){toast('PDF işlemi başarısız: '+e.message)}};
  exportPdf=async function(){const r=APP.report;if(!r)return toast('Önce bir şirket raporu açın.');toast('PDF hazırlanıyor...');await new Promise(resolve=>setTimeout(resolve,40));await presentPdf(`ETS_${safeFileName(r.company.name)}_${r.month}_${r.year}.pdf`,makePdfFromJpegs(reportPdfImages(r)))};
  exportMonthlyPdf=async function(){toast('PDF hazırlanıyor...');await new Promise(resolve=>setTimeout(resolve,40));await presentPdf(`ETS_Aylik_Limit_${APP.month}_${APP.year}.pdf`,makePdfFromJpegs(monthlyPdfImages()))};

  showLogin=function(){APP.report=null;app.innerHTML=`${offlineBar()}<main class="login-wrap"><section class="login-hero"><img src="ets_logo.png" class="ets-logo login-logo" alt="ETS"><h1>ETS Enerji Takip</h1><p>Enerji Takip Sistemi</p></section><section class="card login-panel"><h2>Sisteme Hoş Geldiniz</h2><div class="sub login-help">ETS kullanıcı adı ve parolanızla giriş yapın.</div><div class="login-field">${icon('companies')}<input id="user" class="input" autocomplete="username" value="${esc(localStorage.getItem('etm_user')||'')}" placeholder="Kullanıcı adı"></div><div class="login-field">${icon('settings')}<input id="pass" class="input" type="password" autocomplete="current-password" placeholder="Parola"></div><label class="check"><input id="remember" type="checkbox" ${localStorage.getItem('etm_user')?'checked':''}> Beni Hatırla</label><div class="login-actions"><button id="loginBtn" class="btn btn-primary btn-block" onclick="doLogin()">Giriş Yap</button></div><div id="loginError" class="sub red center" style="margin-top:12px"></div></section><div class="version">Sürüm ${VERSION} • 2026</div></main>`};
  showAbout=function(){app.innerHTML=`${topBar('Hakkında',false)}<main class="page"><img src="ets_logo.png" class="ets-logo about-logo-img" alt="ETS"><div class="center strong" style="font-size:20px">Enerji Takip Sistemi</div><section class="card" style="margin-top:23px"><h3>Uygulama Hakkında</h3><p class="sub" style="font-size:14px">Şirketlerin aylık İND/KAP oranlarını, L1–L3 gerilim ve akım değerlerini, enerji raporlarını ve telefon uyarılarını gösterir.</p><hr style="border:0;border-top:1px solid #eee"><div class="row between"><span>Sürüm</span><b>${VERSION}</b></div><div class="row between" style="margin-top:12px"><span>PDF klasörü</span><b>Downloads/ETS Enerji Takip</b></div></section></main>${bottom('settings')}`};

  if(APP.logged&&APP.companies.length)showDashboard();else showLogin();
})();
