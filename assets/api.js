/*
 * assets/api.js
 * Kleiner API-Client fuer die statische Admin-Oberflaeche.
 *
 * Sicherheitsentscheidung (verbindlich umgesetzt):
 *  - Der Auth-Token wird AUSSCHLIESSLICH in einer Closure-Variablen im Arbeitsspeicher
 *    gehalten. Er wird NIE im persistenten Browser-Speicher, in Cookies oder einer Datei
 *    abgelegt. Ein vollstaendiger Seiten-Reload verwirft den Token (Rueckkehr zur
 *    Anmeldung ist gewollt und akzeptiert).
 *  - Token und Passwort werden niemals geloggt.
 *  - Es findet KEINE clientseitige Passwortpruefung statt. Die Authentifizierung
 *    entscheidet immer das Backend; bei HTTP 401 wird der Token verworfen.
 */
(function () {
  'use strict';

  // In-memory Zustand: nur hier, sonst nirgends.
  var authToken = null;
  var authUsername = null;

  function apiBase() {
    var base = (typeof window.PH_API_BASE === 'string') ? window.PH_API_BASE : '';
    return base.replace(/\/+$/, '');
  }

  // Typisierter Fehler zur Fehlersteuerung in app.js.
  function ApiError(status, message) {
    this.name = 'ApiError';
    this.status = status;               // HTTP-Status, oder 0 bei Netzwerk-/CORS-Fehler
    this.unauthenticated = (status === 401);
    this.message = message || ('Fehler ' + status);
  }
  ApiError.prototype = Object.create(Error.prototype);
  ApiError.prototype.constructor = ApiError;

  function buildHeaders(hasJsonBody) {
    var headers = { 'Accept': 'application/json' };
    if (hasJsonBody) {
      headers['Content-Type'] = 'application/json';
    }
    if (authToken) {
      // Nur der Bearer-Token wird gesendet; er wird nicht protokolliert.
      headers['Authorization'] = 'Bearer ' + authToken;
    }
    return headers;
  }

  function parseJsonSafe(response) {
    return response.text().then(function (textBody) {
      if (!textBody) {
        return null;
      }
      try {
        return JSON.parse(textBody);
      } catch (parseErr) {
        return null;
      }
    }, function () {
      return null;
    });
  }

  function request(method, path, body) {
    var hasBody = (body !== undefined && body !== null);
    var options = {
      method: method,
      credentials: 'omit',            // niemals Cookies senden
      headers: buildHeaders(hasBody)
    };
    if (hasBody) {
      options.body = JSON.stringify(body);
    }

    return fetch(apiBase() + path, options).then(function (response) {
      if (response.status === 401) {
        // Backend lehnt ab: In-memory-Token verwerfen und "nicht angemeldet" signalisieren.
        authToken = null;
        authUsername = null;
        throw new ApiError(401, 'Nicht angemeldet.');
      }
      if (response.status === 204) {
        return null;
      }
      return parseJsonSafe(response).then(function (data) {
        if (!response.ok) {
          var msg = (data && (data.message || data.error))
            ? (data.message || data.error)
            : ('Fehler ' + response.status);
          throw new ApiError(response.status, String(msg));
        }
        return data;
      });
    }, function () {
      // Netzwerk-/CORS-Fehler: keine internen Details, keinen Token loggen.
      throw new ApiError(0, 'Netzwerkfehler: Das Backend ist nicht erreichbar oder CORS blockiert die Anfrage.');
    });
  }

  function buildQuery(params) {
    if (!params) {
      return '';
    }
    var parts = [];
    Object.keys(params).forEach(function (key) {
      var value = params[key];
      if (value === undefined || value === null || value === '') {
        return;
      }
      parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(value)));
    });
    return parts.length ? ('?' + parts.join('&')) : '';
  }

  function login(username, password) {
    // password wird ausschliesslich an das Backend uebertragen, nie verglichen,
    // nie gespeichert, nie geloggt.
    return request('POST', '/api/auth/login', { username: username, password: password })
      .then(function (data) {
        if (data && data.token) {
          authToken = data.token;
          authUsername = data.username || username;
        }
        return { username: authUsername };
      });
  }

  function logout() {
    var pending = authToken
      ? request('POST', '/api/auth/logout', null).then(function () {}, function () {})
      : Promise.resolve();
    return pending.then(function () {
      authToken = null;
      authUsername = null;
    });
  }

  function me() {
    return request('GET', '/api/auth/me', null).then(function (data) {
      if (data && data.username) {
        authUsername = data.username;
      }
      return data;
    });
  }

  function isAuthenticated() {
    return authToken !== null;
  }

  function currentUsername() {
    return authUsername;
  }

  function getTracking(params) {
    return request('GET', '/api/tracking' + buildQuery(params), null);
  }

  function getDelivery(id) {
    return request('GET', '/api/tracking/deliveries/' + encodeURIComponent(id), null);
  }

  function getBatches() {
    return request('GET', '/api/tracking/batches', null);
  }

  window.PhApi = {
    ApiError: ApiError,
    login: login,
    logout: logout,
    me: me,
    isAuthenticated: isAuthenticated,
    currentUsername: currentUsername,
    getTracking: getTracking,
    getDelivery: getDelivery,
    getBatches: getBatches
  };
})();
