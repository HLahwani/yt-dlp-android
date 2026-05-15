// Simulated YouTube player JS fragment — used by decipher unit tests.
// Contains realistic nsig and sig patterns without any real player code.
;(function(){var _yt={};

// ---- nsig (n-param transform) ----
// NParamDecipherer finds the function name via the reference below,
// then extracts the body via JsFunctionExtractor.
var nsig_fn=function(a){var b=a.split(""),c=b.length;void 0!==c&&b.splice(0,1);b.reverse();return b.join("")};
(b=b.get("n"))&&(b=nsig_fn(b));

// ---- nsig via array reference ----
// Used to test the array-element resolution path.
function nsig_named(a){var b=a.split("").reverse();return b.join("")}
var nsig_arr=[nsig_named];
(b=b.get("n"))&&(b=nsig_arr[0](b));

// ---- sig decryption (WEB client fallback) — simple, no helper ----
function sigDecrypt(a){return a.split("").reverse().join("")}
a.sig||sigDecrypt(encSig)

// ---- sig decryption with helper object ----
var Tb={sw:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c},rv:function(a){a.reverse()}};
function sigWithHelper(a){var b=a.split("");Tb.sw(b,52);Tb.rv(b);Tb.sw(b,13);return b.join("")}
a.sig||sigWithHelper(encSig)

})();
