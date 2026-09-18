'use strict';
const $ = (selector) => document.querySelector(selector);
const all = (selector) => [...document.querySelectorAll(selector)];
const storageKey = 'magicpaper-cinematic-tour-v1';
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
const defaults = {model:'chatgpt',search:'auto',plugins:['notes','focus'],step:1};
let choices = {...defaults,plugins:[...defaults.plugins]};
let currentStep = 0;
let motionEnabled = !reducedMotion.matches;
let storageAvailable = true;
let noticeTimer;
function notice(message) {
  $('#notice').textContent = message;
  $('#notice').hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => { $('#notice').hidden = true; }, 6500);
}
try {
  const saved = JSON.parse(localStorage.getItem(storageKey) || 'null');
  if(saved && ['chatgpt','local','api'].includes(saved.model) && ['auto','wikipedia','later'].includes(saved.search) && Array.isArray(saved.plugins) && saved.plugins.every(p=>['notes','focus','calc'].includes(p)) && Number.isInteger(saved.step) && saved.step>=1 && saved.step<=4) {
    choices = {model:saved.model,search:saved.search,plugins:[...new Set(saved.plugins)],step:saved.step};
    $('#start span').textContent = 'Продолжить знакомство';
  }
} catch(error) {
  storageAvailable = false;
  console.warn('Welcome tour: local progress could not be read.', error.name);
  notice('Не удалось восстановить прогресс. Можно пройти знакомство заново; выбор останется на этой странице.');
}
function save() {
  try { localStorage.setItem(storageKey,JSON.stringify(choices)); storageAvailable=true; }
  catch(error) {
    console.warn('Welcome tour: local progress could not be saved.', error.name);
    if(storageAvailable) notice('Браузер не сохранил прогресс. Вы можете продолжить, но после закрытия страницы выбор потеряется.');
    storageAvailable = false;
  }
}
const chapters = [null,
  {kicker:'ГЛАВА I · МОДЕЛЬ',title:'Источник магии',description:'Любой разговор начинается с собеседника.<br>Выберите, кто будет думать вместе с вами.',scene:'ГЛАВА ПЕРВАЯ',sceneTitle:'У каждой истории<br>есть свой голос.',sceneCopy:'Найдите того, кто поможет<br>вашим идеям обрести форму.',id:'model-chapter'},
  {kicker:'ГЛАВА II · ПОИСК',title:'За пределами листа',description:'Хорошая мысль заслуживает надёжных источников.<br>Выберите, откуда будут приходить новые знания.',scene:'ГЛАВА ВТОРАЯ',sceneTitle:'Мир больше,<br>чем одна книга.',sceneCopy:'Откройте дверь к знаниям,<br>которые ещё предстоит найти.',id:'search-chapter'},
  {kicker:'ГЛАВА III · ПЛАГИНЫ',title:'Ваши маленькие чудеса',description:'Соберите помощника под свой ритм.<br>Какие возможности возьмём с собой?',scene:'ГЛАВА ТРЕТЬЯ',sceneTitle:'Большие идеи.<br>Маленькие помощники.',sceneCopy:'Всё нужное — под рукой.<br>Всё остальное может подождать.',id:'plugins-chapter'}
];
const modelNotes = {chatgpt:'В приложении вы войдёте в ChatGPT. Для этого варианта API-ключ не нужен.',local:'В приложении укажите адрес Ollama или LM Studio и имя установленной модели. Сервер должен быть запущен.',api:'В приложении выберите провайдера и укажите API-ключ и модель. На этой учебной странице ключ вводить не нужно.'};
const searchNotes = {auto:'Автопоиск использует настроенные Google и Querit, затем Wikipedia. Дополнительные источники можно подключить позже.',wikipedia:'Энциклопедия помогает начать исследование. Проверяйте даты и первоисточники, когда актуальность имеет значение.',later:'Вы сможете подключить поиск в настройках MagicPaper, когда он понадобится.'};
function syncChoices() {
  all('input[name=model]').forEach(input=>input.checked=input.value===choices.model);
  all('input[name=search]').forEach(input=>input.checked=input.value===choices.search);
  all('input[name=plugin]').forEach(input=>input.checked=choices.plugins.includes(input.value));
  $('#model-note').textContent=modelNotes[choices.model];
  $('#search-note').textContent=searchNotes[choices.search];
}
function renderSummary() {
  const names={chatgpt:'ChatGPT',local:'Локальная модель',api:'Облачный API',auto:'Автопоиск',wikipedia:'Wikipedia',later:'Поиск — позже',notes:'Заметки',focus:'Фокус',calc:'Счёты'};
  $('#summary').replaceChildren(...[choices.model,choices.search,...choices.plugins].map(value=>{
    const span=document.createElement('span');
    span.innerHTML='<svg class="icon" aria-hidden="true"><use href="#check"/></svg>';
    span.append(document.createTextNode(names[value]));
    return span;
  }));
  if(!choices.plugins.length) { const span=document.createElement('span');span.textContent='Без плагинов';$('#summary').append(span); }
}
function render(step) {
  currentStep=step;
  const view=step===0?'intro':step<=3?'journey':step===4?'finale':'practice';
  all('.view').forEach(node=>node.hidden=node.id!==view);
  document.body.dataset.view=view;
  all('[data-go]').forEach(button=>{
    const active=Number(button.dataset.go)===step;
    if(active) button.setAttribute('aria-current','step'); else button.removeAttribute('aria-current');
  });
  $('#skip').hidden=step>=4;
  if(step>0&&step<=3) {
    const chapter=chapters[step];
    $('#chapter-kicker').textContent=chapter.kicker;
    $('#chapter-title').textContent=chapter.title;
    $('#chapter-description').innerHTML=chapter.description;
    $('#scene-chapter').textContent=chapter.scene;
    $('#scene-title').innerHTML=chapter.sceneTitle;
    $('#scene-copy').innerHTML=chapter.sceneCopy;
    $('#chapter-count').textContent=`0${step} / 03`;
    $('#progress-line').style.width=`${step/3*100}%`;
    all('.chapter-content').forEach(node=>node.hidden=node.id!==chapter.id);
    $('#next span').textContent=step===3?'Завершить знакомство':'Следующая глава';
    choices.step=step;
    save();
  }
  if(step===4) { renderSummary();choices.step=4;save(); }
  document.title=(step>0&&step<=3?chapters[step].title:step===5?'Ваш первый вопрос':'Ваша история начинается здесь')+' — MagicPaper';
  window.scrollTo({top:0,behavior:'instant'});
  const heading=$(`#${view} h1`);
  heading.focus({preventScroll:true});
}
let sceneAnimation=null;
function go(step) {
  step=Math.max(0,Math.min(5,step));
  if(step===currentStep)return;
  // Animate live content, not a snapshot overlay: navigation remains clickable
  // throughout the dissolve, and a new action interrupts the previous movement.
  sceneAnimation?.cancel();
  render(step);
  if(motionEnabled && !reducedMotion.matches) {
    const target=step>=1&&step<=3 ? $('.chapter-body') : $('#main');
    sceneAnimation=target.animate([{opacity:.25,transform:'translateY(10px)'},{opacity:1,transform:'translateY(0)'}],{duration:650,easing:'cubic-bezier(.22,1,.36,1)'});
  }
}
all('[data-go]').forEach(button=>button.addEventListener('click',()=>go(Number(button.dataset.go))));
$('#home').addEventListener('click',()=>go(0));
$('#start').addEventListener('click',()=>go(choices.step));
$('#skip').addEventListener('click',()=>{ $('#final-description').innerHTML='Начните в своём темпе.<br>К выбору модели, поиска и плагинов можно вернуться.';go(4); });
$('#back').addEventListener('click',()=>go(currentStep-1));
$('#next').addEventListener('click',()=>{ $('#final-description').innerHTML='Вы познакомились с MagicPaper.<br>Вот ваш стартовый набор.';go(currentStep+1); });
$('#restart').addEventListener('click',()=>go(1));
$('#example-button').addEventListener('click',()=>go(5));
$('#practice-back').addEventListener('click',()=>go(4));
all('input').forEach(input=>input.addEventListener('change',()=>{
  if(input.name==='plugin') choices.plugins=all('input[name=plugin]:checked').map(node=>node.value);
  else choices[input.name]=input.value;
  syncChoices();save();
}));
const examples={
  plan:'<h2>Оставьте место для важного.</h2><ol><li>Выберите одну главную задачу на сегодня.</li><li>Отведите ей 25 минут без отвлечений.</li><li>Сделайте короткий перерыв и запишите следующий шаг.</li></ol><p>Начать проще, когда следующий шаг маленький.</p>',
  idea:'<h2>Дайте идее первую форму.</h2><ol><li>Опишите, кому и какую проблему она поможет решить.</li><li>Выберите один результат, который можно показать.</li><li>Сделайте небольшой прототип и попросите обратную связь.</li></ol><p>Первый черновик важнее идеального плана.</p>',
  learn:'<h2>Как работает языковая модель?</h2><p>Представьте собеседника, который прочитал множество примеров текста и научился замечать в них закономерности. По вашему вопросу модель строит ответ, выбирая продолжение шаг за шагом.</p><p>Она может помогать с объяснениями и идеями, но способна ошибаться. Важные факты стоит проверять.</p>'
};
all('[data-example]').forEach(button=>button.addEventListener('click',()=>{
  all('[data-example]').forEach(node=>node.setAttribute('aria-pressed',String(node===button)));
  $('#sample-answer').innerHTML=examples[button.dataset.example];
}));

// A single decorative canvas; no network, timers in hidden tabs, or per-frame DOM work.
const canvas=$('#dust');
const ctx=canvas.getContext('2d');
const particles=Array.from({length:42},(_,i)=>({x:((i*137.508)%100)/100,y:((i*79.31)%100)/100,r:.5+(i%4)*.45,s:.003+(i%5)*.001,phase:i*1.7}));
let frame=0,lastFrame=0,elapsed=0,width=0,height=0;
function resize(){const dpr=Math.min(devicePixelRatio||1,2);width=innerWidth;height=innerHeight;canvas.width=width*dpr;canvas.height=height*dpr;if(ctx)ctx.setTransform(dpr,0,0,dpr,0,0);}
function draw(now){
  const dt=lastFrame?Math.min((now-lastFrame)/1000,.05):0;lastFrame=now;elapsed+=dt;
  if(ctx){ctx.clearRect(0,0,width,height);for(const p of particles){const y=(p.y-elapsed*p.s%1+1)%1;const alpha=.15+(Math.sin(elapsed*.7+p.phase)+1)*.2;ctx.beginPath();ctx.fillStyle=`rgba(240,207,148,${alpha})`;ctx.arc(p.x*width+Math.sin(elapsed*.2+p.phase)*14,y*height,p.r,0,Math.PI*2);ctx.fill();}}
  frame=requestAnimationFrame(draw);
}
function updateMotion(){
  const active=motionEnabled&&!reducedMotion.matches;
  document.body.classList.toggle('no-motion',!active);
  $('#motion').setAttribute('aria-pressed',String(active));
  $('#motion-label').textContent=active?'Анимация':'Без анимации';
  cancelAnimationFrame(frame);lastFrame=0;
  if(!active)sceneAnimation?.cancel();
  if(active&&!document.hidden&&ctx) frame=requestAnimationFrame(draw);
  else if(ctx)ctx.clearRect(0,0,width,height);
}
$('#motion').addEventListener('click',()=>{
  if(reducedMotion.matches){notice('В системных настройках включено уменьшение движения. Анимация отключена.');return;}
  motionEnabled=!motionEnabled;updateMotion();
});
reducedMotion.addEventListener('change',updateMotion);
window.addEventListener('resize',resize);
document.addEventListener('visibilitychange',()=>{updateMotion();if(audioContext&&soundEnabled){const result=document.hidden?audioContext.suspend():audioContext.resume();result.catch(audioFailure);}});

// Original quiet ambient chord, synthesized locally only after an explicit click.
let audioContext=null,soundEnabled=false,master=null;
function audioFailure(error){console.warn('Welcome tour: audio unavailable.',error.name);soundEnabled=false;updateSoundLabel();notice('Звук недоступен в этом браузере. Знакомство можно продолжить без него.');}
function updateSoundLabel(){$('#sound-toggle').setAttribute('aria-pressed',String(soundEnabled));$('#sound-label').textContent=soundEnabled?'Звук включён':'Звук выключен';}
$('#sound-toggle').addEventListener('click',async()=>{
  try{
    if(!audioContext){
      const Audio=window.AudioContext||window.webkitAudioContext;
      if(!Audio)throw new Error('UnsupportedAudio');
      audioContext=new Audio();master=audioContext.createGain();master.gain.value=0;master.connect(audioContext.destination);
      [130.81,196,261.63,293.66,392,523.25].forEach((frequency,i)=>{
        const oscillator=audioContext.createOscillator(),gain=audioContext.createGain();
        oscillator.type='sine';oscillator.frequency.value=frequency;oscillator.detune.value=i%2?3:-3;gain.gain.value=.14/(i+1);
        oscillator.connect(gain);gain.connect(master);oscillator.start();
      });
    }
    await audioContext.resume();soundEnabled=!soundEnabled;
    master.gain.setTargetAtTime(soundEnabled ? .28 : 0,audioContext.currentTime,.65);updateSoundLabel();
  }catch(error){audioFailure(error);}
});
window.addEventListener('pagehide',()=>{cancelAnimationFrame(frame);if(audioContext)audioContext.suspend().catch(audioFailure);});
window.addEventListener('pageshow',updateMotion);
syncChoices();resize();updateMotion();document.body.dataset.view='intro';
