package com.ytdlpdroid.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ytdlpdroid.decipher.PlayerJsRepository
import com.ytdlpdroid.js.QuickJsEngine
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlayerJsAnalysisTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun repo() = PlayerJsRepository(
        File(ctx.cacheDir, "ytdlpdroid_analysis").also { it.mkdirs() })

    @Test
    fun probeBeforeException() {
        val playerJsUrl = "/s/player/25f11721/player-plasma-es6-en_US.vflset/base.js"
        val pjs = runBlocking { repo().fetchPlayerJs(playerJsUrl) }
        println("length=${pjs.length}")

        val shim = """
var self=globalThis;
if(typeof document==='undefined'){var document={createElement:function(){return{style:{},setAttribute:function(){},appendChild:function(){},addEventListener:function(){},classList:{add:function(){},remove:function(){}}};},getElementById:function(){return null;},querySelector:function(){return null;},querySelectorAll:function(){return{length:0,forEach:function(){}};},head:{appendChild:function(){},querySelector:function(){return null;},insertBefore:function(){}},body:{appendChild:function(){},insertBefore:function(){}},addEventListener:function(){},createTextNode:function(t){return{textContent:t};}}}
if(typeof navigator==='undefined'){var navigator={userAgent:'Mozilla/5.0',platform:'Win32',language:'en-US',languages:['en-US'],cookieEnabled:true}}
if(typeof location==='undefined'){var location={href:'https://www.youtube.com/',origin:'https://www.youtube.com',hostname:'www.youtube.com',protocol:'https:',pathname:'/',search:'',hash:''}}
if(typeof localStorage==='undefined'){var localStorage={getItem:function(){return null;},setItem:function(){},removeItem:function(){},clear:function(){},length:0}}
if(typeof sessionStorage==='undefined'){var sessionStorage={getItem:function(){return null;},setItem:function(){},removeItem:function(){}}}
if(typeof performance==='undefined'){var performance={now:function(){return Date.now();},timing:{navigationStart:0}}}
if(typeof XMLHttpRequest==='undefined'){var XMLHttpRequest=function(){this.open=function(){};this.send=function(){};this.setRequestHeader=function(){};this.addEventListener=function(){};}}
if(typeof fetch==='undefined'){var fetch=function(){return Promise.reject(new Error('no fetch'));}}
if(typeof MutationObserver==='undefined'){var MutationObserver=function(fn){this.observe=function(){};this.disconnect=function(){};}}
if(typeof requestAnimationFrame==='undefined'){var requestAnimationFrame=function(fn){return 0;};}
if(typeof screen==='undefined'){var screen={width:1920,height:1080,colorDepth:24};}
if(typeof crypto==='undefined'){var crypto={getRandomValues:function(a){for(var i=0;i<a.length;i++)a[i]=Math.floor(Math.random()*256);return a;}};}
""".trimIndent()

        val engine = QuickJsEngine()
        // Injection point: just before the exception (177979 confirmed working)
        val injectPos = pjs.lastIndexOf(';', 177979) + 1
        println("injection pos: $injectPos")

        // Test simple probes first
        fun probe(hookJs: String, readKey: String): String? {
            val p = pjs.substring(0, injectPos) + hookJs + pjs.substring(injectPos)
            return engine.executeWithPlayerJs(shim + "\n" + p, readKey)
        }

        println("\n--- Simple probes at pos $injectPos ---")
        println("typeof g: ${probe(";globalThis.s1=typeof g;", "globalThis.s1")}")
        println("Object.keys(g).length: ${probe(";globalThis.s2=Object.keys(g).length;", "globalThis.s2")}")
        println("typeof wD: ${probe(";globalThis.s3=typeof wD;", "globalThis.s3")}")
        println("typeof uF: ${probe(";globalThis.s4=typeof uF;", "globalThis.s4")}")

        // Get first 20 keys of g that are 2-4 chars
        println("\n--- 2-4 char keys of g ---")
        val keysProbe = """
;try{
    var k24=Object.keys(g).filter(function(k){return k.length>=2&&k.length<=4;});
    globalThis.s5=k24.length+':'+k24.slice(0,20).join(',');
}catch(e){globalThis.s5='err:'+e;}
"""
        println("2-4 char keys: ${probe(keysProbe, "globalThis.s5")}")

        // Test each 2-4 char key individually for nsig-like behavior
        println("\n--- Test nsig candidates (2-4 char g keys returning base64url) ---")
        val ti = "abcdefghijklmnopq" // 17-char test
        val ti2 = "qponmlkjihgfedcbaZ" // different input
        val nsigProbe = """
;try{
    var ti="$ti",ti2="$ti2",found=[];
    var k24=Object.keys(g).filter(function(k){return k.length>=2&&k.length<=4&&typeof g[k]==='function';});
    for(var i=0;i<k24.length;i++){
        try{
            var k=k24[i],fn=g[k];
            var r1=fn(ti),r2=fn(ti2);
            if(typeof r1==='string'&&r1!==ti&&r1!=='undefined'&&r1.indexOf('=')<0&&r1.indexOf('/')<0&&r1.length>=5&&r1.length<=ti.length+2&&r1!==r2)
                found.push(k+'->'+r1);
        }catch(e){}
    }
    globalThis.s6=found.length+': '+found.slice(0,5).join(';');
}catch(e){globalThis.s6='outer_err:'+e;}
"""
        println("nsig candidates: ${probe(nsigProbe, "globalThis.s6")}")

        // What's around position 177979-181123? Print context
        println("\n--- Context around exception (177979-181123) ---")
        println(pjs.substring(177900, minOf(pjs.length, 181200)))
    }
}
