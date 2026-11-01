package dev.relay.claude

/**
 * Скрипт, который WebView вставляет в каждую страницу и фрейм до загрузки её кода.
 *
 * Делает ровно одно — вырезает WebRTC (второй слой; первый — сетевая «защита»,
 * она и так глушит UDP). WebRTC умеет светить настоящий IP в обход прокси.
 * Вложенные фреймы about:blank (классический обход) патчатся так же.
 *
 * Чего здесь сознательно НЕТ — подмены часового пояса и языка скриптом.
 * Пробовали в разработке: подмена Date/Intl работает идеально (56/56 совпадений
 * с настоящей Ригой), но Cloudflare замечает расхождение с фоновыми потоками
 * страницы и не пускает в Claude — проверка «я человек» крутится по кругу.
 * Язык ru-LV ставится системно (RelayApp.applyLocale), без скриптов.
 */
object PrivacyScript {

    val SCRIPT: String = """
(function () {
  'use strict';
  var RTC = ['RTCPeerConnection', 'webkitRTCPeerConnection', 'mozRTCPeerConnection',
             'RTCDataChannel', 'RTCIceCandidate', 'RTCSessionDescription', 'RTCRtpSender',
             'RTCRtpReceiver', 'RTCRtpTransceiver', 'RTCDtlsTransport', 'RTCIceTransport',
             'RTCSctpTransport', 'RTCCertificate', 'RTCDTMFSender', 'RTCEncodedAudioFrame'];
  var done = new WeakSet();

  function kill(w) {
    if (!w || done.has(w)) return;
    try { done.add(w); } catch (e) { return; }
    for (var i = 0; i < RTC.length; i++) {
      try { delete w[RTC[i]]; } catch (e) {}
      try { Object.defineProperty(w, RTC[i], { get: function () { return undefined; }, set: function () {}, configurable: false }); } catch (e) {}
    }
  }

  kill(window);

  // вложенные фреймы (about:blank) — классический обход, глушим и их
  try {
    var P = HTMLIFrameElement.prototype;
    var cw = Object.getOwnPropertyDescriptor(P, 'contentWindow');
    var cd = Object.getOwnPropertyDescriptor(P, 'contentDocument');
    Object.defineProperty(P, 'contentWindow', { configurable: true, enumerable: true,
      get: function () { var w = cw.get.call(this); kill(w); return w; } });
    Object.defineProperty(P, 'contentDocument', { configurable: true, enumerable: true,
      get: function () { var d = cd.get.call(this); if (d) kill(d.defaultView); return d; } });
  } catch (e) {}
})();
"""
}
