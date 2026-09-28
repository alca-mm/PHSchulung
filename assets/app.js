/*
 * assets/app.js
 * SPA-Controller der Admin-Oberflaeche (admin/index.html).
 *
 * Warum eine Single-Page-App:
 *  - Der Auth-Token lebt nur im Arbeitsspeicher (siehe api.js). Ein echter Seitenwechsel
 *    (Navigation zu einer anderen HTML-Datei) wuerde den Token verwerfen. Deshalb bleibt
 *    der Nutzer auf admin/index.html; die Ansichten werden per Hash-Routing gewechselt:
 *      #dashboard (Standard), #delivery/{id}, #batches.
 *  - Ist kein Token vorhanden (z.B. nach Reload), wird die Anmeldeansicht gezeigt.
 *
 * Sicherheit gegen XSS:
 *  - API-Daten werden ausschliesslich ueber sichere DOM-APIs (textContent/createElement/
 *    setAttribute) gerendert. Es wird nirgends Roh-HTML zugewiesen.
 */
(function () {
  'use strict';

  var appRoot = null;
  var headerUser = null;
  var logoutButton = null;
  var refreshButton = null;
  var connStatus = null;

  // Filter-/Sortierzustand des Dashboards (im Speicher gehalten).
  var filterState = {
    query: '',
    batchId: '',
    fileName: '',
    status: '',
    reacted: '',
    from: '',
    to: '',
    sort: 'SENT_AT',
    dir: 'DESC'
  };

  // ---- Formatierung -------------------------------------------------------

  var tsFormatter = new Intl.DateTimeFormat('de-DE', {
    timeZone: 'Europe/Berlin',
    dateStyle: 'medium',
    timeStyle: 'medium'
  });

  function formatTimestamp(iso) {
    if (!iso) {
      return '-';
    }
    var d = new Date(iso);
    if (isNaN(d.getTime())) {
      return '-';
    }
    return tsFormatter.format(d);
  }

  function formatCount(value) {
    if (value === null || value === undefined || isNaN(value)) {
      return '-';
    }
    return Number(value).toLocaleString('de-DE');
  }

  function formatDecimal(value) {
    if (value === null || value === undefined || isNaN(value)) {
      return '-';
    }
    return (Math.round(Number(value) * 100) / 100).toLocaleString('de-DE');
  }

  function formatRate(value) {
    if (value === null || value === undefined || isNaN(value)) {
      return '-';
    }
    // Robust: Bruchwert (0..1) wird zu Prozent, bereits prozentuale Werte bleiben.
    var pct = (Number(value) <= 1) ? Number(value) * 100 : Number(value);
    return (Math.round(pct * 10) / 10).toLocaleString('de-DE') + ' %';
  }

  function formatBool(value) {
    return value ? 'Ja' : 'Nein';
  }

  function statusLabel(status) {
    switch (status) {
      case 'SENT': return 'Gesendet';
      case 'FAILED': return 'Fehlgeschlagen';
      case 'NOT_SENT': return 'Nicht gesendet';
      default: return status ? String(status) : '-';
    }
  }

  function orDash(value) {
    return (value === null || value === undefined || value === '') ? '-' : String(value);
  }

  function apiBaseLabel() {
    var base = window.PH_API_BASE;
    return (typeof base === 'string' && base.trim() !== '') ? base : '(nicht konfiguriert)';
  }

  // ---- DOM-Hilfen (kein Roh-HTML) ----------------------------------------

  function appendChildren(node, children) {
    if (children === null || children === undefined || children === false) {
      return;
    }
    if (Array.isArray(children)) {
      children.forEach(function (child) { appendChildren(node, child); });
      return;
    }
    if (children instanceof Node) {
      node.appendChild(children);
      return;
    }
    node.appendChild(document.createTextNode(String(children)));
  }

  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (key) {
        var value = attrs[key];
        if (value === null || value === undefined) {
          return;
        }
        if (key === 'class') {
          node.className = value;
        } else if (key === 'text') {
          node.textContent = value;
        } else if (key === 'dataset') {
          Object.keys(value).forEach(function (dk) { node.dataset[dk] = value[dk]; });
        } else if (key === 'onClick') {
          node.addEventListener('click', value);
        } else if (key === 'onSubmit') {
          node.addEventListener('submit', value);
        } else {
          node.setAttribute(key, value);
        }
      });
    }
    appendChildren(node, children);
    return node;
  }

  function clearNode(node) {
    while (node.firstChild) {
      node.removeChild(node.firstChild);
    }
  }

  function setView(node) {
    clearNode(appRoot);
    appRoot.appendChild(node);
  }

  function messageBox(kind, message) {
    return el('div', { class: 'msg msg-' + kind, role: 'alert' }, message);
  }

  function loadingView(label) {
    return el('div', { class: 'loading' }, label || 'Wird geladen ...');
  }

  // ---- Kopfzeile / Auth-Status -------------------------------------------

  function updateHeaderAuth(authenticated) {
    if (authenticated) {
      var name = window.PhApi.currentUsername();
      headerUser.textContent = name ? ('Angemeldet als ' + name) : 'Angemeldet';
      logoutButton.hidden = false;
      if (refreshButton) { refreshButton.hidden = false; }
    } else {
      headerUser.textContent = '';
      logoutButton.hidden = true;
      if (refreshButton) { refreshButton.hidden = true; }
      setConnection(null);
    }
  }

  /** Verbindungsanzeige in der Kopfzeile: true=Verbunden, false=nicht erreichbar, null=leer. */
  function setConnection(state) {
    if (!connStatus) { return; }
    if (state === true) {
      connStatus.textContent = 'Verbunden';
      connStatus.className = 'conn-status conn-ok';
    } else if (state === false) {
      connStatus.textContent = 'Backend nicht erreichbar';
      connStatus.className = 'conn-status conn-bad';
    } else {
      connStatus.textContent = '';
      connStatus.className = 'conn-status';
    }
  }

  /**
   * Prueft die (oeffentliche) Konfiguration und die Browser-Sicherheitslage. Gibt eine sichtbare Warnung
   * zurueck oder null. Behebt/umgeht KEINE Browsersicherheit - erklaert sie nur.
   */
  function configWarning() {
    var base = window.PH_API_BASE;
    if (typeof base !== 'string' || base.trim() === '') {
      return 'Keine Backend-Adresse konfiguriert. Bitte PH_API_BASE in assets/config.js setzen.';
    }
    var parsed;
    try {
      parsed = new URL(base);
    } catch (e) {
      return 'Die konfigurierte Backend-Adresse (PH_API_BASE) ist ungueltig. Bitte in assets/config.js korrigieren.';
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      return 'Die Backend-Adresse (PH_API_BASE) muss mit dem Schema http oder https beginnen.';
    }
    // Mixed Content: eine ueber HTTPS ausgelieferte Seite (z.B. GitHub Pages) darf kein HTTP-Backend aufrufen.
    if (window.location.protocol === 'https:' && parsed.protocol === 'http:') {
      return 'Das konfigurierte lokale Backend (' + base + ') kann von dieser HTTPS-Seite nicht sicher erreicht '
        + 'werden (Mixed Content). Fuer den zuverlaessigen Betrieb ueber GitHub Pages ist ein per HTTPS '
        + 'erreichbares Backend erforderlich. Ein lokales Backend laesst sich weiterhin ueber ein lokal '
        + 'ausgeliefertes Frontend testen.';
    }
    return null;
  }

  // ---- Fehlerbehandlung ---------------------------------------------------

  function handleApiError(err, viewLabel) {
    if (err && err.unauthenticated) {
      updateHeaderAuth(false);
      showLogin('Die Sitzung ist abgelaufen. Bitte erneut anmelden.');
      return;
    }
    var text;
    var retry = null;
    if (err && err.status === 404) {
      text = 'Datensatz wurde nicht gefunden.';
    } else if (err && err.status === 403) {
      text = 'Zugriff verweigert.';
    } else if (err && err.status === 429) {
      text = 'Zu viele Anfragen. Bitte kurz warten.';
    } else if (err && err.status === 400) {
      text = 'Ungueltige Anfrage (400)' + (err.message ? (': ' + err.message) : '.');
    } else if (err && err.status === 0) {
      setConnection(false);
      text = 'Die Tracking-API ist aktuell nicht erreichbar (Backend: ' + apiBaseLabel() + '). '
        + 'Bitte pruefen: Backend erreichbar? CORS-Freigabe fuer diese Seite? connect-src der CSP '
        + '(admin/index.html) auf die Backend-Adresse gesetzt?';
      retry = true;
    } else if (err && err.status >= 500) {
      text = 'Backend-Fehler. Bitte spaeter erneut versuchen.';
      retry = true;
    } else {
      text = (err && err.message) ? err.message : 'Unbekannter Fehler.';
    }
    var children = [
      el('h2', { text: viewLabel || 'Fehler' }),
      messageBox('error', text)
    ];
    if (retry) {
      children.push(el('button', {
        type: 'button', class: 'btn btn-primary',
        onClick: function () { route(); }
      }, 'Erneut versuchen'));
    }
    children.push(navBar());
    var container = el('section', { class: 'card' }, children);
    setView(container);
  }

  // ---- Anmeldeansicht -----------------------------------------------------

  function showLogin(infoMessage, warnMessage) {
    updateHeaderAuth(false);

    var errorSlot = el('div', { class: 'login-error' });

    var usernameField = el('input', {
      type: 'text', id: 'login-username', name: 'username',
      autocomplete: 'username', required: 'required'
    });
    var passwordField = el('input', {
      type: 'password', id: 'login-password', name: 'current-password',
      autocomplete: 'current-password', required: 'required'
    });
    var submitButton = el('button', { type: 'submit', class: 'btn btn-primary' }, 'Anmelden');

    var loginSubmitting = false;
    var form = el('form', {
      class: 'login-form', novalidate: 'novalidate',
      onSubmit: function (event) {
        event.preventDefault();
        // Doppel-Submit (z.B. schnelles Enter) explizit verhindern - zusaetzlich zum Button-Disable.
        if (loginSubmitting) {
          return;
        }
        loginSubmitting = true;
        clearNode(errorSlot);
        submitButton.disabled = true;
        submitButton.textContent = 'Anmeldung laeuft ...';

        var username = usernameField.value;
        var password = passwordField.value;

        window.PhApi.login(username, password).then(function () {
          // Passwortfeld nicht im DOM belassen.
          passwordField.value = '';
          updateHeaderAuth(true);
          setConnection(true);
          navigateTo('dashboard');
        }, function (err) {
          loginSubmitting = false;
          submitButton.disabled = false;
          submitButton.textContent = 'Anmelden';
          var text;
          if (err && err.status === 401) {
            text = 'Benutzername oder Passwort ist falsch.';
          } else if (err && err.status === 429) {
            text = 'Zu viele Anmeldeversuche. Bitte kurz warten.';
          } else if (err && err.status === 0) {
            setConnection(false);
            text = err.message || 'Netzwerkfehler: Backend nicht erreichbar.';
          } else {
            text = (err && err.message) ? err.message : 'Anmeldung fehlgeschlagen.';
          }
          errorSlot.appendChild(messageBox('error', text));
        });
      }
    }, [
      el('div', { class: 'field' }, [
        el('label', { for: 'login-username', text: 'Benutzername' }),
        usernameField
      ]),
      el('div', { class: 'field' }, [
        el('label', { for: 'login-password', text: 'Passwort' }),
        passwordField
      ]),
      submitButton
    ]);

    var card = el('section', { class: 'card login-card' }, [
      el('h1', { text: 'Admin-Anmeldung' }),
      el('p', { class: 'muted', text: 'Bitte mit den internen Zugangsdaten anmelden. Die Pruefung erfolgt serverseitig.' }),
      warnMessage ? messageBox('warn', warnMessage) : null,
      infoMessage ? messageBox('info', infoMessage) : null,
      errorSlot,
      form
    ]);

    setView(card);
  }

  // ---- Navigation ---------------------------------------------------------

  function navigateTo(hashView) {
    var target = '#' + hashView;
    if (window.location.hash === target) {
      route();
    } else {
      window.location.hash = target;
    }
  }

  function navBar() {
    return el('nav', { class: 'viewnav' }, [
      el('a', { class: 'btn btn-link', href: '#dashboard', text: 'Uebersicht' }),
      el('a', { class: 'btn btn-link', href: '#batches', text: 'Batch-Auswertung' }),
      // Link zur oeffentlichen Awareness-Seite (eine Ebene ueber /admin/).
      el('a', { class: 'btn btn-link', href: '../index.html', text: 'Oeffentliche Seite' })
    ]);
  }

  function parseHash() {
    var raw = (window.location.hash || '').replace(/^#/, '');
    if (raw.indexOf('delivery/') === 0) {
      var rawId = raw.slice('delivery/'.length);
      var id;
      try {
        id = decodeURIComponent(rawId);
      } catch (e) {
        // Ungueltige Prozent-Sequenz im Hash (z.B. #delivery/%): robust auf den Rohwert zurueckfallen,
        // niemals eine unbehandelte URIError-Ausnahme aus dem Routing werfen.
        id = rawId;
      }
      return { view: 'delivery', id: id };
    }
    if (raw === 'batches') {
      return { view: 'batches' };
    }
    return { view: 'dashboard' };
  }

  function route() {
    if (!window.PhApi.isAuthenticated()) {
      showLogin();
      return;
    }
    updateHeaderAuth(true);
    var current = parseHash();
    if (current.view === 'delivery') {
      renderDelivery(current.id);
    } else if (current.view === 'batches') {
      renderBatches();
    } else {
      renderDashboard();
    }
  }

  // ---- Dashboard ----------------------------------------------------------

  function kpiCard(label, value) {
    return el('div', { class: 'kpi' }, [
      el('div', { class: 'kpi-value', text: value }),
      el('div', { class: 'kpi-label', text: label })
    ]);
  }

  function kpiGrid(summary) {
    var s = summary || {};
    return el('div', { class: 'kpi-grid' }, [
      kpiCard('Getrackte Zustellungen', formatCount(s.trackedDeliveries)),
      kpiCard('Gesendet', formatCount(s.sentDeliveries)),
      kpiCard('Reagiert', formatCount(s.reactedDeliveries)),
      kpiCard('Nicht reagiert', formatCount(s.notReactedDeliveries)),
      kpiCard('Aktionen gesamt', formatCount(s.totalActions)),
      kpiCard('Reaktionsquote', formatRate(s.reactionRate)),
      kpiCard('Aktionen im Schnitt', formatDecimal(s.averageActions))
    ]);
  }

  function selectField(labelText, name, value, options) {
    var select = el('select', { name: name, id: 'f-' + name });
    options.forEach(function (opt) {
      var attrs = { value: opt.value };
      if (String(opt.value) === String(value === null || value === undefined ? '' : value)) {
        attrs.selected = 'selected';
      }
      select.appendChild(el('option', attrs, opt.label));
    });
    return el('div', { class: 'field' }, [
      el('label', { for: 'f-' + name, text: labelText }),
      select
    ]);
  }

  function textField(labelText, name, value, type) {
    return el('div', { class: 'field' }, [
      el('label', { for: 'f-' + name, text: labelText }),
      el('input', { type: type || 'text', name: name, id: 'f-' + name, value: value || '' })
    ]);
  }

  function filterForm(batches) {
    var batchOptions = [{ value: '', label: 'Alle Batches' }];
    (batches || []).forEach(function (b) {
      batchOptions.push({ value: b.id, label: b.label });
    });

    var form = el('form', {
      class: 'filter-form',
      onSubmit: function (event) {
        event.preventDefault();
        var data = new FormData(form);
        filterState.query = (data.get('query') || '').toString().trim();
        filterState.batchId = (data.get('batchId') || '').toString();
        filterState.fileName = (data.get('fileName') || '').toString().trim();
        filterState.status = (data.get('status') || '').toString();
        filterState.reacted = (data.get('reacted') || '').toString();
        filterState.from = (data.get('from') || '').toString();
        filterState.to = (data.get('to') || '').toString();
        renderDashboard();
      }
    }, [
      textField('Name / E-Mail', 'query', filterState.query),
      selectField('Batch', 'batchId', filterState.batchId, batchOptions),
      textField('Datei', 'fileName', filterState.fileName),
      selectField('Status', 'status', filterState.status, [
        { value: '', label: 'Alle' },
        { value: 'NOT_SENT', label: 'Nicht gesendet' },
        { value: 'SENT', label: 'Gesendet' },
        { value: 'FAILED', label: 'Fehlgeschlagen' }
      ]),
      selectField('Reaktion', 'reacted', filterState.reacted, [
        { value: '', label: 'Alle' },
        { value: 'ALL', label: 'Alle (explizit)' },
        { value: 'REACTED', label: 'Reagiert' },
        { value: 'NOT_REACTED', label: 'Nicht reagiert' }
      ]),
      textField('Von', 'from', filterState.from, 'date'),
      textField('Bis', 'to', filterState.to, 'date'),
      el('div', { class: 'filter-actions' }, [
        el('button', { type: 'submit', class: 'btn btn-primary' }, 'Filtern'),
        el('button', {
          type: 'button', class: 'btn',
          onClick: function () {
            filterState.query = '';
            filterState.batchId = '';
            filterState.fileName = '';
            filterState.status = '';
            filterState.reacted = '';
            filterState.from = '';
            filterState.to = '';
            renderDashboard();
          }
        }, 'Zuruecksetzen')
      ])
    ]);
    return form;
  }

  function sortableHeader(labelText, sortKey) {
    var isActive = (filterState.sort === sortKey);
    var indicator = '';
    if (isActive) {
      indicator = (filterState.dir === 'ASC') ? ' ↑' : ' ↓';
    }
    var button = el('button', {
      type: 'button',
      class: 'sort-btn' + (isActive ? ' active' : ''),
      onClick: function () {
        if (filterState.sort === sortKey) {
          filterState.dir = (filterState.dir === 'ASC') ? 'DESC' : 'ASC';
        } else {
          filterState.sort = sortKey;
          filterState.dir = 'DESC';
        }
        renderDashboard();
      }
    }, labelText + indicator);
    // aria-sort fuer Screenreader (ascending/descending/none) zusaetzlich zum sichtbaren Pfeil.
    var ariaSort = isActive ? (filterState.dir === 'ASC' ? 'ascending' : 'descending') : 'none';
    return el('th', { scope: 'col', 'aria-sort': ariaSort }, button);
  }

  function plainHeader(labelText) {
    return el('th', { scope: 'col' }, labelText);
  }

  function trackingTable(rows) {
    var thead = el('thead', null, el('tr', null, [
      plainHeader('Name'),
      plainHeader('E-Mail'),
      plainHeader('Batch'),
      plainHeader('Betreff'),
      plainHeader('Datei'),
      plainHeader('Status'),
      sortableHeader('Gesendet', 'SENT_AT'),
      plainHeader('Reagiert'),
      sortableHeader('Erster Klick', 'FIRST_CLICK'),
      sortableHeader('Letzter Klick', 'LAST_CLICK'),
      sortableHeader('Aktionen', 'ACTIONS'),
      plainHeader('Details')
    ]));

    var tbody = el('tbody');
    if (!rows || rows.length === 0) {
      tbody.appendChild(el('tr', null, el('td', { colspan: '12', class: 'empty' }, 'Keine Eintraege gefunden.')));
    } else {
      rows.forEach(function (r) {
        var detailsLink = el('a', {
          class: 'btn btn-link',
          href: '#delivery/' + encodeURIComponent(r.deliveryId),
          text: 'Details'
        });
        tbody.appendChild(el('tr', null, [
          el('td', null, orDash(r.contactName)),
          el('td', null, orDash(r.contactEmail)),
          el('td', null, orDash(r.batchId)),
          el('td', null, orDash(r.subject)),
          el('td', null, orDash(r.attachmentFilename)),
          el('td', null, el('span', { class: 'badge badge-' + (r.deliveryStatus || 'NA') }, statusLabel(r.deliveryStatus))),
          el('td', null, formatTimestamp(r.sentAt)),
          el('td', null, formatBool(r.reacted)),
          el('td', null, formatTimestamp(r.firstClick)),
          el('td', null, formatTimestamp(r.lastClick)),
          el('td', null, formatCount(r.clickCount)),
          el('td', null, detailsLink)
        ]));
      });
    }

    return el('div', { class: 'table-wrap' }, el('table', { class: 'data-table' }, [thead, tbody]));
  }

  function renderDashboard() {
    setView(loadingView('Dashboard wird geladen ...'));

    var params = {
      query: filterState.query,
      batchId: filterState.batchId,
      fileName: filterState.fileName,
      status: filterState.status,
      reacted: filterState.reacted,
      from: filterState.from,
      to: filterState.to,
      sort: filterState.sort,
      dir: filterState.dir
    };

    window.PhApi.getTracking(params).then(function (data) {
      var d = data || {};
      var container = el('section', { class: 'view' }, [
        el('div', { class: 'view-head' }, [
          el('h1', { text: 'Tracking-Uebersicht' }),
          el('a', { class: 'btn', href: '#batches', text: 'Batch-Auswertung' })
        ]),
        kpiGrid(d.summary),
        el('section', { class: 'card' }, [
          el('h2', { text: 'Filter' }),
          filterForm(d.batches)
        ]),
        el('section', { class: 'card' }, [
          el('h2', { text: 'Zustellungen' }),
          trackingTable(d.rows)
        ])
      ]);
      setView(container);
      setConnection(true);
    }, function (err) {
      handleApiError(err, 'Tracking-Uebersicht');
    });
  }

  // ---- Detailansicht ------------------------------------------------------

  function defRow(term, value) {
    return [el('dt', { text: term }), el('dd', null, value)];
  }

  function renderDelivery(id) {
    setView(loadingView('Details werden geladen ...'));

    window.PhApi.getDelivery(id).then(function (data) {
      var d = data || {};
      var recipient = d.recipient || {};
      var delivery = d.delivery || {};
      var tracking = d.tracking || {};
      var timeline = Array.isArray(d.timeline) ? d.timeline.slice() : [];

      timeline.sort(function (a, b) {
        var ta = a.occurredAt ? new Date(a.occurredAt).getTime() : 0;
        var tb = b.occurredAt ? new Date(b.occurredAt).getTime() : 0;
        return ta - tb;
      });

      var timelineNode;
      if (timeline.length === 0) {
        timelineNode = el('p', { class: 'muted', text: 'Keine Ereignisse erfasst.' });
      } else {
        var list = el('ol', { class: 'timeline' });
        timeline.forEach(function (event) {
          list.appendChild(el('li', null, [
            el('span', { class: 'timeline-type', text: orDash(event.type) }),
            el('span', { class: 'timeline-time', text: formatTimestamp(event.occurredAt) })
          ]));
        });
        timelineNode = list;
      }

      var container = el('section', { class: 'view' }, [
        el('div', { class: 'view-head' }, [
          el('h1', { text: 'Zustellungsdetails' }),
          el('a', { class: 'btn', href: '#dashboard', text: 'Zurueck zur Uebersicht' })
        ]),
        el('section', { class: 'card' }, [
          el('h2', { text: 'Empfaenger' }),
          el('dl', { class: 'deflist' }, [
            defRow('Name', orDash(recipient.name)),
            defRow('E-Mail', orDash(recipient.email))
          ])
        ]),
        el('section', { class: 'card' }, [
          el('h2', { text: 'Zustellung' }),
          el('dl', { class: 'deflist' }, [
            defRow('Zustellungs-ID', orDash(delivery.deliveryId)),
            defRow('Batch', orDash(delivery.batchId)),
            defRow('Betreff', orDash(delivery.subject)),
            defRow('Datei', orDash(delivery.attachmentFilename)),
            defRow('Status', statusLabel(delivery.deliveryStatus)),
            defRow('Gesendet', formatTimestamp(delivery.sentAt)),
            defRow('Versuche', formatCount(delivery.attemptCount))
          ])
        ]),
        el('section', { class: 'card' }, [
          el('h2', { text: 'Tracking' }),
          el('dl', { class: 'deflist' }, [
            defRow('Reagiert', formatBool(tracking.reacted)),
            defRow('Erster Klick', formatTimestamp(tracking.firstClick)),
            defRow('Letzter Klick', formatTimestamp(tracking.lastClick)),
            defRow('Aktionen', formatCount(tracking.clickCount))
          ])
        ]),
        el('section', { class: 'card' }, [
          el('h2', { text: 'Zeitleiste' }),
          timelineNode
        ])
      ]);
      setView(container);
      setConnection(true);
    }, function (err) {
      handleApiError(err, 'Zustellungsdetails');
    });
  }

  // ---- Batch-Auswertung ---------------------------------------------------

  function batchesTable(batches) {
    var thead = el('thead', null, el('tr', null, [
      'Batch', 'Betreff', 'Datei', 'Erstellt', 'Empfaenger', 'Gesendet',
      'Fehlgeschlagen', 'Nicht gesendet', 'Reagiert', 'Reaktionsquote',
      'Aktionen', 'Erstes Ereignis', 'Letztes Ereignis'
    ].map(function (h) { return el('th', { scope: 'col' }, h); })));

    var tbody = el('tbody');
    if (!batches || batches.length === 0) {
      tbody.appendChild(el('tr', null, el('td', { colspan: '13', class: 'empty' }, 'Keine Batches vorhanden.')));
    } else {
      batches.forEach(function (b) {
        tbody.appendChild(el('tr', null, [
          el('td', null, orDash(b.batchId)),
          el('td', null, orDash(b.subject)),
          el('td', null, orDash(b.attachmentFilename)),
          el('td', null, formatTimestamp(b.createdAt)),
          el('td', null, formatCount(b.recipientCount)),
          el('td', null, formatCount(b.sentCount)),
          el('td', null, formatCount(b.failedCount)),
          el('td', null, formatCount(b.notSentCount)),
          el('td', null, formatCount(b.reactedRecipients)),
          el('td', null, formatRate(b.reactionRate)),
          el('td', null, formatCount(b.totalActions)),
          el('td', null, formatTimestamp(b.firstEvent)),
          el('td', null, formatTimestamp(b.lastEvent))
        ]));
      });
    }

    return el('div', { class: 'table-wrap' }, el('table', { class: 'data-table' }, [thead, tbody]));
  }

  function renderBatches() {
    setView(loadingView('Batch-Auswertung wird geladen ...'));

    window.PhApi.getBatches().then(function (data) {
      var batches = Array.isArray(data) ? data : [];
      var container = el('section', { class: 'view' }, [
        el('div', { class: 'view-head' }, [
          el('h1', { text: 'Batch-Auswertung' }),
          el('a', { class: 'btn', href: '#dashboard', text: 'Zurueck zur Uebersicht' })
        ]),
        el('section', { class: 'card' }, [
          batchesTable(batches)
        ])
      ]);
      setView(container);
      setConnection(true);
    }, function (err) {
      handleApiError(err, 'Batch-Auswertung');
    });
  }

  // ---- Initialisierung ----------------------------------------------------

  function init() {
    appRoot = document.getElementById('app');
    headerUser = document.getElementById('current-user');
    logoutButton = document.getElementById('logout-btn');
    refreshButton = document.getElementById('refresh-btn');
    connStatus = document.getElementById('conn-status');

    logoutButton.addEventListener('click', function () {
      logoutButton.disabled = true;
      window.PhApi.logout().then(function () {
        logoutButton.disabled = false;
        updateHeaderAuth(false);
        showLogin('Sie wurden abgemeldet.');
      });
    });

    if (refreshButton) {
      refreshButton.addEventListener('click', function () {
        if (window.PhApi.isAuthenticated()) {
          route();
        }
      });
    }

    window.addEventListener('hashchange', route);

    // Beim Laden liegt kein Token vor (nur im Speicher): Anmeldeansicht zeigen - mit sichtbarer Warnung, falls
    // die (oeffentliche) Konfiguration/Browsersituation den Backend-Zugriff verhindert. So bleibt die Seite
    // immer sichtbar und erklaert den Zustand, statt leer zu sein.
    showLogin(null, configWarning());
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
