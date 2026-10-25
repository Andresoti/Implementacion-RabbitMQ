import { effect, inject, Injectable, signal } from '@angular/core';
import { InMemoryWebStorage, NavigateResponse, User, UserManager, UserManagerSettings, WebStorageStateStore } from 'oidc-client-ts';
import { CONFIG, RuntimeConfig } from './config';
export function authSettings(config: RuntimeConfig): UserManagerSettings {
 return { authority: config.authority, client_id: config.clientId, redirect_uri: config.redirectUri,
 response_type: 'code', scope: config.scopes, disablePKCE: false, automaticSilentRenew: false, requestTimeoutInSeconds:15,
 stateStore: new WebStorageStateStore({store: sessionStorage}),
 userStore: new WebStorageStateStore({store: new InMemoryWebStorage()}) };
}
@Injectable({providedIn:'root'})
export class AuthService {
 private generation=0;
 async callback(url: string): Promise<void> {
  const generation=++this.generation;
  this.replaceManager();
  const manager=this.manager;
  // Scrub once before awaiting the provider; never overwrite later navigation.
  history.replaceState({}, document.title, new URL(this.config.redirectUri).pathname);
  try {
   const user=await this.withDeadline(()=>manager.signinRedirectCallback(url));
   if (generation !== this.generation) throw new Error('Session changed');
   this.user.set(user);
  } catch {
   this.failOperation(generation,manager);
   throw new Error('Sign-in could not be verified. Please sign in again.');
  }
 }
 readonly user = signal<User | null>(null);
 constructor() {
  effect(onCleanup => {
   const user=this.user();
   if (!user) return;
   if (!this.token) { this.user.set(null); return; }
   const expire=() => {
    if (this.user() !== user) return;
    const remaining=(user.expires_at ?? 0)*1000-Date.now();
    if (remaining <= 0) this.user.set(null);
    else timer=setTimeout(expire, Math.min(remaining,2147483647));
   };
   let timer:ReturnType<typeof setTimeout>;
   expire();
   onCleanup(()=>clearTimeout(timer));
  });
 }
 get token(): string | null {
  const user=this.user();
  return user && typeof user.expires_at === 'number' && Number.isFinite(user.expires_at) && !user.expired
   && typeof user.access_token === 'string' && !!user.access_token.trim() && (user.scope === undefined || typeof user.scope === 'string')
   && typeof user.token_type === 'string' && user.token_type.toLowerCase() === 'bearer' ? user.access_token : null;
 }
 hasScope(scope: string): boolean { return !!this.token && !!this.user()?.scope?.split(/\s+/).includes(scope); }
 async logout(navigate = (url: string) => location.assign(url)): Promise<void> {
  this.generation++;
  this.user.set(null);
  const manager=this.manager;
  this.replaceManager();
  await manager.removeUser();
  const url = new URL('/logout', this.config.logoutDomain);
  url.searchParams.set('client_id', this.config.clientId);
  url.searchParams.set('logout_uri', this.config.logoutRedirectUri);
  navigate(url.toString());
 }
 private failOperation(generation:number,manager:UserManager):void {
  if (generation !== this.generation) return;
  this.generation++;
  this.user.set(null);
  this.replaceManager();
  // Local invalidation must not wait for (or reject with) library cleanup.
  void Promise.resolve().then(()=>manager.removeUser()).catch(()=>{});
 }
 private async withDeadline<T>(operation:()=>Promise<T>):Promise<T> {
  // One deadline covers every request, including response-body consumption.
  let timer:ReturnType<typeof setTimeout> | undefined;
  const deadline=new Promise<never>((_,reject)=>{ timer=setTimeout(()=>reject(new Error('Sign-in timed out')),15000); });
  try { return await Promise.race([operation(),deadline]); }
  finally { clearTimeout(timer); }
 }
 private readonly config = inject(CONFIG);
 private invalidateManager=()=>{};
 private manager=this.createManager();
 private replaceManager():void {
  this.invalidateManager();
  this.manager=this.createManager();
 }
 private createManager():UserManager {
  let active=true;
  const settings=authSettings(this.config);
  const store=settings.userStore!;
  // Reject before the library can store a late user or raise userLoaded.
  settings.userStore={
   set:async(key,value)=>{ if (!active) throw new Error('Session changed'); await store.set(key,value); },
   get:async key=>active ? store.get(key) : null,
   remove:key=>store.remove(key),
   getAllKeys:()=>store.getAllKeys()
  };
  let closeRedirect=()=>{};
  const manager=new UserManager(settings,{
   prepare:async()=>({
    navigate:async({url})=>{
     if (!active) throw new Error('Session changed');
     return new Promise<NavigateResponse>((resolve,reject)=>{
      const cleanup=()=>{ window.removeEventListener('pageshow',shown); closeRedirect=()=>{}; };
      const shown=()=>{ cleanup(); resolve({url:window.location.href}); };
      closeRedirect=()=>{ cleanup(); reject(new Error('Session changed')); };
      window.addEventListener('pageshow',shown);
      try { window.self.location.assign(url); }
      catch (error) { cleanup(); reject(error); }
     });
    },
    close:()=>closeRedirect()
   }),
   callback:async()=>{}
  });
  this.invalidateManager=()=>{
   active=false; closeRedirect();
   void manager.events.unload().catch(()=>{});
  };
  return manager;
 }
 async login():Promise<void> {
  const generation=++this.generation;
  this.replaceManager();
  const manager=this.manager;
  try {
   await this.withDeadline(async()=>{
    await manager.clearStaleState();
    if (generation !== this.generation) throw new Error('Session changed');
    await manager.signinRedirect({nonce:crypto.randomUUID()});
   });
   if (generation !== this.generation) throw new Error('Session changed');
  } catch {
   this.failOperation(generation,manager);
   throw new Error('Sign-in could not start. Please try again.');
  }
 }
}
