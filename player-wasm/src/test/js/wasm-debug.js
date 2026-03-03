'use strict';
const fs=require('fs'), path=require('path'), vm=require('vm');
const distDir='player-wasm/build/dist';
const dcrFile='C:/xampp/htdocs/dcr/14.1_b8/habbo.dcr';
const castDir='C:/xampp/htdocs/dcr/14.1_b8';
const sw1='external.variables.txt=http://localhost/gamedata/external_variables.txt;external.texts.txt=http://localhost/gamedata/external_texts.txt';

global.performance=global.performance||require('perf_hooks').performance;
global.TextEncoder=global.TextEncoder||require('util').TextEncoder;
global.TextDecoder=global.TextDecoder||require('util').TextDecoder;
global.document={createElement:()=>({set src(v){},set onload(v){},set onerror(v){}}),head:{appendChild:()=>{}},getElementsByTagName:()=>[],readyState:'complete',currentScript:null};
global.window=global;
global.fetch=async url=>{const s=String(url),lp=s.startsWith('http')?path.join(castDir,s.split('/').pop().split('?')[0]):s;try{const d=fs.readFileSync(lp);return{ok:true,status:200,arrayBuffer:async()=>d.buffer.slice(d.byteOffset,d.byteOffset+d.byteLength),text:async()=>d.toString('utf8')};}catch(e){return{ok:false,status:404,arrayBuffer:async()=>new ArrayBuffer(0),text:async()=>''};} };
WebAssembly.instantiateStreaming=async(fp,imp)=>{const r=await fp;return WebAssembly.instantiate(await r.arrayBuffer(),imp);};
vm.runInThisContext(fs.readFileSync(path.join(distDir,'player-wasm.wasm-runtime.js'),'utf8'));

async function run(){
    const teavm=await TeaVM.wasm.load(path.join(distDir,'player-wasm.wasm'));
    await teavm.main([]);
    const exp=teavm.instance.exports,mem=teavm.memory;
    const dec=new TextDecoder(),enc=new TextEncoder();
    const cx=()=>{if(exp.teavm_catchException)exp.teavm_catchException();};
    const rj=l=>{if(!l)return null;const a=exp.getLargeBufferAddress();return a?JSON.parse(dec.decode(new Uint8Array(mem.buffer,a,l))):null;};
    function deliver(reqs){
        for(const req of reqs||[]){
            const fn=req.url.split('/').pop().split('?')[0];
            const lp=path.join(castDir,fn);
            try{const d=fs.readFileSync(lp);const a=exp.allocateNetBuffer(d.length);new Uint8Array(mem.buffer,a,d.length).set(d);exp.deliverFetchResult(req.taskId,d.length);cx();}
            catch(e){
                let ok=false;
                for(const fb of(req.fallbacks||[])){
                    const fn2=fb.split('/').pop().split('?')[0];
                    try{const d=fs.readFileSync(path.join(castDir,fn2));const a=exp.allocateNetBuffer(d.length);new Uint8Array(mem.buffer,a,d.length).set(d);exp.deliverFetchResult(req.taskId,d.length);cx();ok=true;break;}catch(e2){}
                }
                if(!ok){exp.deliverFetchError(req.taskId,404);cx();}
            }
        }
    }
    function pump(label){
        const cnt=exp.getPendingFetchCount();cx();
        if(!cnt)return;
        const l=exp.getPendingFetchJson();cx();
        const r=rj(l);exp.drainPendingFetches();cx();
        for(const req of r||[])console.log(label,'NET:',req.url,'fallbacks:',req.fallbacks);
        deliver(r);
    }

    const db=fs.readFileSync(dcrFile);
    const bp=enc.encode('http://localhost/habbo.dcr');
    const sb=exp.getStringBufferAddress();
    new Uint8Array(mem.buffer,sb,bp.length).set(bp);
    const ba=exp.allocateBuffer(db.length);new Uint8Array(mem.buffer,ba,db.length).set(db);
    exp.loadMovie(db.length,bp.length);cx();
    const kb=enc.encode('sw1'),vb=enc.encode(sw1);
    const sbuf=new Uint8Array(mem.buffer,sb,4096);sbuf.set(kb);sbuf.set(vb,kb.length);
    exp.setExternalParam(kb.length,vb.length);cx();
    exp.preloadCasts();cx();pump('preload');
    exp.play();cx();pump('play');

    let maxS=0,finalF=0;
    for(let i=0;i<2000;i++){
        exp.tick();cx();
        pump('tick'+i);
        if(i%200===0||i<5){
            const l2=exp.getFrameDataJson();cx();const fd=rj(l2);
            if(fd){const s=(fd.sprites||[]).length;if(s>maxS)maxS=s;finalF=fd.frame||0;console.log('tick',i,'frame=',finalF,'sprites=',s);}
        }
    }
    console.log('DONE maxS=',maxS,'finalF=',finalF);
}
run().catch(e=>{console.error(e);process.exit(1);});
