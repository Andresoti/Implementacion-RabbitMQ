import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { OidcClient, UserManager, User } from 'oidc-client-ts';
import { AuthService, authSettings } from './auth';
import { CONFIG, parseConfig } from './config';
const config = parseConfig({authority:'https://authority.example/pool',clientId:'public',logoutDomain:'https://login.example',redirectUri:'http://localhost:4200/callback',logoutRedirectUri:'http://localhost:4200/',apiBaseUrl:'http://localhost:8080',scopes:'openid email pedidos/read pedidos/write'});
describe('OIDC session', () => {
 beforeEach(() => { sessionStorage.clear(); TestBed.configureTestingModule({providers:[{provide:CONFIG,useValue:config}]}); });
 it('integrates real PKCE transaction consumption and nonce rejection through the OIDC library',async()=> {
  const metadata={issuer:config.authority,authorization_endpoint:'https://login.example/oauth2/authorize',token_endpoint:'https://login.example/oauth2/token',userinfo_endpoint:'https://login.example/userinfo'};
  const client=new OidcClient({...authSettings(config),metadata});
  const request=await client.createSigninRequest({nonce:'expected-nonce',state:{route:'orders'}});
  const authorization=new URL(request.url);
  expect(authorization.searchParams.get('code_challenge_method')).toBe('S256');
  expect(authorization.searchParams.get('code_challenge')).toMatch(/^[A-Za-z0-9_-]{43}$/);
  expect(authorization.searchParams.has('code_verifier')).toBe(false); expect(authorization.searchParams.get('nonce')).toBe('expected-nonce');
  const encode=(value:unknown)=>btoa(JSON.stringify(value)).replace(/=/g,'').replace(/\+/g,'-').replace(/\//g,'_');
  const badId=encode({alg:'RS256',typ:'JWT'})+'.'+encode({iss:config.authority,aud:config.clientId,sub:'subject',iat:Math.floor(Date.now()/1000),exp:Math.floor(Date.now()/1000)+300,nonce:'wrong-nonce'})+'.test-signature';
  const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async(input,init)=> {
   const url=String(input);
   if(url.includes('.well-known')) return new Response(JSON.stringify(metadata),{headers:{'Content-Type':'application/json'}});
   expect(url).toBe(metadata.token_endpoint);
   expect(String(init?.body)).toContain('code_verifier=');
   return new Response(JSON.stringify({access_token:'test-access',id_token:badId,token_type:'Bearer',expires_in:300,scope:config.scopes}),{headers:{'Content-Type':'application/json'}});
  });
  const callbackSpy=vi.spyOn(UserManager.prototype,'signinRedirectCallback');
  try {
   const auth=TestBed.inject(AuthService);
   await expect(auth.callback(config.redirectUri+'?code=test&state='+authorization.searchParams.get('state'))).rejects.toThrow(/could not be verified/);
   expect(fetcher).toHaveBeenCalledTimes(2);
   await expect(callbackSpy.mock.results[0].value).rejects.toThrow(/nonce/i);
   expect(auth.token).toBeNull();
   expect(await authSettings(config).stateStore!.get(authorization.searchParams.get('state')!)).toBeNull();
  } finally { fetcher.mockRestore(); callbackSpy.mockRestore(); }
 });
 it.each(['pending','rejected'])('bounds the entire callback to 15 seconds even when user removal is %s', async removal => {
  vi.useFakeTimers();
  const callback=vi.spyOn(UserManager.prototype,'signinRedirectCallback').mockImplementation(()=>new Promise(()=>{}));
  const remove=vi.spyOn(UserManager.prototype,'removeUser').mockImplementation(()=>removal === 'pending' ? new Promise(()=>{}) : Promise.reject(new Error('cleanup failed')));
  const replace=vi.spyOn(history,'replaceState');
  try {
   const auth=TestBed.inject(AuthService);
   const result=vi.fn();
   const pending=auth.callback(config.redirectUri+'?code=secret&state=transaction').then(()=>result('success'),error=>result(error.message));
   await vi.advanceTimersByTimeAsync(14999); expect(result).not.toHaveBeenCalled();
   await vi.advanceTimersByTimeAsync(1);
   expect(result).toHaveBeenCalledWith('Sign-in could not be verified. Please sign in again.');
   await pending; expect(auth.user()).toBeNull();
   expect(replace).toHaveBeenCalledWith({},document.title,'/callback');
   expect(vi.getTimerCount()).toBe(0);
  } finally { callback.mockRestore(); remove.mockRestore(); replace.mockRestore(); vi.useRealTimers(); }
 });
 it.each([
  ['discovery','timeout'],['token','timeout'],['userinfo','timeout'],['token','logout']
 ])('invalidates late library storage and userLoaded events after a %s body and %s',async(phase,ending)=> {
  vi.useFakeTimers();
  const metadata={issuer:config.authority,authorization_endpoint:'https://login.example/oauth2/authorize',token_endpoint:'https://login.example/oauth2/token',userinfo_endpoint:'https://login.example/userinfo'};
  const encode=(value:unknown)=>btoa(JSON.stringify(value)).replace(/=/g,'').replace(/\+/g,'-').replace(/\//g,'_');
  const idToken=encode({alg:'RS256',typ:'JWT'})+'.'+encode({iss:config.authority,aud:config.clientId,sub:'subject',iat:Math.floor(Date.now()/1000),exp:Math.floor(Date.now()/1000)+300,nonce:'expected-nonce'})+'.signature';
  let release!:()=>void;
  const token={access_token:'late-access',id_token:idToken,token_type:'Bearer',expires_in:300,scope:config.scopes};
  const responses={discovery:metadata,token,userinfo:{sub:'subject'}};
  const body=new ReadableStream<Uint8Array>({start(controller){ release=()=>{controller.enqueue(new TextEncoder().encode(JSON.stringify(responses[phase as keyof typeof responses])));controller.close();}; }});
  const fetcher=vi.spyOn(globalThis,'fetch').mockImplementation(async input=>{
   const current=String(input).includes('.well-known') ? 'discovery' : String(input) === metadata.userinfo_endpoint ? 'userinfo' : 'token';
   if (current === phase) await new Promise(resolve=>setTimeout(resolve,10000));
   return new Response(current === phase ? body : JSON.stringify(responses[current]),{headers:{'Content-Type':'application/json'}});
  });
  const processSigninResponse=OidcClient.prototype.processSigninResponse;
  const process=vi.spyOn(OidcClient.prototype,'processSigninResponse').mockImplementation(function(this:OidcClient,...args){
   // Exercise the library's optional userinfo phase without enabling it in production.
   if (phase === 'userinfo') Object.assign(this.settings,{loadUserInfo:true});
   return processSigninResponse.apply(this,args);
  });
  const callback=vi.spyOn(UserManager.prototype,'signinRedirectCallback');
  try {
   const request=await new OidcClient({...authSettings(config),metadata}).createSigninRequest({nonce:'expected-nonce'});
   const auth=TestBed.inject(AuthService);
   const result=vi.fn();
   const pending=auth.callback(config.redirectUri+'?code=x&state='+new URL(request.url).searchParams.get('state')).catch(error=>result(error.message));
   await vi.advanceTimersByTimeAsync(10000);
   const manager=callback.mock.contexts[0] as UserManager; const loaded=vi.fn(); manager.events.addUserLoaded(loaded);
   expect(fetcher).toHaveBeenCalledTimes(phase === 'discovery' ? 1 : phase === 'token' ? 2 : 3);
   if (ending === 'logout') await auth.logout(()=>{});
   await vi.advanceTimersByTimeAsync(4999); expect(result).not.toHaveBeenCalled();
   await vi.advanceTimersByTimeAsync(1); await pending;
   expect(result).toHaveBeenCalledWith('Sign-in could not be verified. Please sign in again.');
   release(); await Promise.allSettled([callback.mock.results[0].value]);
   expect(loaded).not.toHaveBeenCalled();
   expect(await manager.getUser()).toBeNull(); expect(auth.token).toBeNull();
   expect(vi.getTimerCount()).toBe(0);
  } finally { process.mockRestore(); fetcher.mockRestore(); callback.mockRestore(); vi.useRealTimers(); }
 });
 it('verifies callbacks through the OIDC library, cleans the URL and checks session expiry and exact scopes', async () => {
  const user = new User({access_token:'access',token_type:'Bearer',scope:'openid pedidos/read pedidos/write',expires_at:Math.floor(Date.now()/1000)+300,profile:{sub:'subject',iss:config.authority,aud:config.clientId,exp:9999999999,iat:1}});
  const callback = vi.spyOn(UserManager.prototype,'signinRedirectCallback').mockResolvedValue(user);
  const replace = vi.spyOn(history,'replaceState');
  const auth = TestBed.inject(AuthService);
  await auth.callback('http://localhost:4200/callback?code=secret&state=transaction');
  expect(callback).toHaveBeenCalledWith('http://localhost:4200/callback?code=secret&state=transaction');
  expect(auth.token).toBe('access'); expect(auth.hasScope('pedidos/read')).toBe(true); expect(auth.hasScope('pedidos')).toBe(false);
  expect(replace).toHaveBeenCalledWith({},document.title,'/callback');
  user.expires_at = 1; expect(auth.token).toBeNull(); expect(auth.hasScope('pedidos/read')).toBe(false);
  callback.mockRestore(); replace.mockRestore();
 });
 it('rejects a callback without a saved state and cleans sensitive URL parameters on failure', async () => {
  const replace = vi.spyOn(history,'replaceState');
  const auth = TestBed.inject(AuthService);
  await expect(auth.callback('http://localhost:4200/callback?code=bad&state=unknown')).rejects.toThrow();
  expect(auth.user()).toBeNull(); expect(replace).toHaveBeenCalledWith({},document.title,'/callback');
  replace.mockRestore();
 });
 it('awaits local removal before navigating to Cognito logout', async () => {
  let finish!: () => void;
  const remove = vi.spyOn(UserManager.prototype,'removeUser').mockImplementation(() => new Promise<void>(resolve => {finish = resolve;}));
  const auth = TestBed.inject(AuthService); const navigate = vi.fn();
  const pending = auth.logout(navigate); expect(navigate).not.toHaveBeenCalled();
  finish(); await pending;
  const url = new URL(navigate.mock.calls[0][0]);
  expect(url.origin).toBe(config.logoutDomain); expect(url.pathname).toBe('/logout');
  expect(url.searchParams.get('client_id')).toBe(config.clientId); expect(url.searchParams.get('logout_uri')).toBe(config.logoutRedirectUri);
  expect(auth.token).toBeNull(); remove.mockRestore();
 });
 it('reactively clears the session when the access token expires', async () => {
  vi.useFakeTimers();
  try {
   const auth=TestBed.inject(AuthService);
   auth.user.set(new User({access_token:'access',token_type:'Bearer',scope:'pedidos/read',expires_at:Math.floor(Date.now()/1000)+2,profile:{sub:'subject',iss:config.authority,aud:'public',iat:1,exp:9999999999}}));
   TestBed.tick();
   await vi.advanceTimersByTimeAsync(2100);
   expect(auth.user()).toBeNull();
  } finally { vi.useRealTimers(); }
 });
 it.each([{expires_at:Infinity},{expires_at:'9999999999'},{expires_at:NaN},{access_token:7},{access_token:''},{token_type:'Basic'},{scope:['pedidos/read']}])('fails closed on malformed session fields %j', fields => {
  const auth=TestBed.inject(AuthService);
  const user=new User({access_token:'access',token_type:'Bearer',scope:'pedidos/read',expires_at:Math.floor(Date.now()/1000)+300,profile:{sub:'subject',iss:config.authority,aud:'public',iat:1,exp:9999999999}});
  Object.assign(user,fields); auth.user.set(user);
  expect(auth.token).toBeNull(); expect(auth.hasScope('pedidos/read')).toBe(false);
  TestBed.tick(); expect(auth.user()).toBeNull();
 });
 it('does not restore a callback that finishes after local logout', async () => {
  let finish!:(user:User)=>void;
  const callback=vi.spyOn(UserManager.prototype,'signinRedirectCallback').mockImplementation(()=>new Promise(resolve=>{finish=resolve;}));
  const auth=TestBed.inject(AuthService);
  const pending=auth.callback(config.redirectUri+'?code=x&state=y');
  await auth.logout(()=>{});
  const assertion=expect(pending).rejects.toThrow(/sign in again/i);
  finish(new User({access_token:'access',token_type:'Bearer',scope:'pedidos/read',expires_at:Math.floor(Date.now()/1000)+300,profile:{sub:'subject',iss:config.authority,aud:'public',iat:1,exp:9999999999}}));
  await assertion; expect(auth.user()).toBeNull(); callback.mockRestore();
 });
 it.each(['stale state','redirect'])('bounds login including %s without restarting the 15-second deadline',async phase=> {
  vi.useFakeTimers();
  let finish!:()=>void;
  const stale=vi.spyOn(UserManager.prototype,'clearStaleState').mockImplementation(()=>new Promise(resolve=>{finish=resolve;}));
  const redirect=vi.spyOn(UserManager.prototype,'signinRedirect').mockImplementation(()=>new Promise(()=>{}));
  try {
   const auth=TestBed.inject(AuthService); const result=vi.fn();
   const pending=auth.login().then(()=>result('success'),error=>result(error.message));
   await vi.advanceTimersByTimeAsync(10000);
   if (phase === 'redirect') { finish(); await vi.advanceTimersByTimeAsync(0); expect(redirect).toHaveBeenCalledOnce(); }
   await vi.advanceTimersByTimeAsync(4999); expect(result).not.toHaveBeenCalled();
   await vi.advanceTimersByTimeAsync(1);
   expect(result).toHaveBeenCalledWith('Sign-in could not start. Please try again.');
   await pending;
   if (phase === 'stale state') { finish(); await vi.advanceTimersByTimeAsync(0); expect(redirect).not.toHaveBeenCalled(); }
   expect(auth.token).toBeNull(); expect(vi.getTimerCount()).toBe(0);
  } finally { stale.mockRestore(); redirect.mockRestore(); vi.useRealTimers(); }
 });
 it('prevents a late real discovery body from navigating after login timed out',async()=> {
  vi.useFakeTimers();
  const assign=vi.fn();
  const self=vi.spyOn(window,'self','get').mockReturnValue({location:{assign}} as unknown as Window & typeof globalThis);
  let release!:()=>void;
  const body=new ReadableStream<Uint8Array>({start(controller){ release=()=>{controller.enqueue(new TextEncoder().encode(JSON.stringify({issuer:config.authority,authorization_endpoint:'https://login.example/authorize'})));controller.close();}; }});
  const request=vi.spyOn(OidcClient.prototype,'createSigninRequest');
  const fetcher=vi.spyOn(globalThis,'fetch').mockResolvedValue(new Response(body,{headers:{'Content-Type':'application/json'}}));
  try {
   const auth=TestBed.inject(AuthService); const result=vi.fn();
   const pending=auth.login().catch(error=>result(error.message));
   await vi.advanceTimersByTimeAsync(14999); expect(fetcher).toHaveBeenCalledOnce(); expect(result).not.toHaveBeenCalled();
   await vi.advanceTimersByTimeAsync(1); await pending;
   expect(result).toHaveBeenCalledWith('Sign-in could not start. Please try again.');
   release(); await request.mock.results[0].value; await vi.advanceTimersByTimeAsync(0);
   expect(assign).not.toHaveBeenCalled(); expect(auth.user()).toBeNull();
   expect(vi.getTimerCount()).toBe(0);
  } finally { window.dispatchEvent(new Event('pageshow')); request.mockRestore(); fetcher.mockRestore(); self.mockRestore(); vi.useRealTimers(); }
 });
 it.each(['pageshow','navigation failure','timeout'])('cleans up a real redirect navigator after %s',async ending=> {
  vi.useFakeTimers();
  const assign=vi.fn(()=>{ if (ending === 'navigation failure') throw new Error('navigation blocked'); });
  const self=vi.spyOn(window,'self','get').mockReturnValue({location:{assign}} as unknown as Window & typeof globalThis);
  const request=vi.spyOn(OidcClient.prototype,'createSigninRequest');
  const fetcher=vi.spyOn(globalThis,'fetch').mockResolvedValue(new Response(JSON.stringify({issuer:config.authority,authorization_endpoint:'https://login.example/authorize'}),{headers:{'Content-Type':'application/json'}}));
  const removeListener=vi.spyOn(window,'removeEventListener');
  try {
   const auth=TestBed.inject(AuthService); const result=vi.fn();
   const pending=auth.login().then(()=>result('success'),error=>result(error.message));
   await vi.advanceTimersByTimeAsync(0); await request.mock.results[0].value; await vi.advanceTimersByTimeAsync(0);
   expect(assign).toHaveBeenCalledOnce();
   if (ending === 'pageshow') { expect(result).not.toHaveBeenCalled(); window.dispatchEvent(new Event('pageshow')); }
   if (ending === 'timeout') { expect(result).not.toHaveBeenCalled(); await vi.advanceTimersByTimeAsync(15000); }
   await pending;
   expect(result).toHaveBeenCalledWith(ending === 'pageshow' ? 'success' : 'Sign-in could not start. Please try again.');
   expect(removeListener.mock.calls.some(([event])=>event === 'pageshow')).toBe(true);
   expect(vi.getTimerCount()).toBe(0);
  } finally { request.mockRestore(); fetcher.mockRestore(); self.mockRestore(); removeListener.mockRestore(); vi.useRealTimers(); }
 });
 it('unloads retired manager token timers when a later callback fails',async()=> {
  vi.useFakeTimers();
  const user=new User({access_token:'access',token_type:'Bearer',expires_at:Math.floor(Date.now()/1000)+300,profile:{sub:'subject',iss:config.authority,aud:'public',iat:1,exp:9999999999}});
  const callback=vi.spyOn(UserManager.prototype,'signinRedirectCallback').mockImplementation(async function(this:UserManager){ await this.storeUser(user); await this.events.load(user); return user; });
  try {
   const auth=TestBed.inject(AuthService); await auth.callback(config.redirectUri+'?code=x&state=y');
   expect(vi.getTimerCount()).toBeGreaterThan(0);
   callback.mockRejectedValue(new Error('invalid callback'));
   await expect(auth.callback(config.redirectUri+'?code=bad&state=z')).rejects.toThrow(/sign in again/i);
   expect(auth.token).toBeNull(); TestBed.tick(); await vi.advanceTimersByTimeAsync(0); expect(vi.getTimerCount()).toBe(0);
  } finally { callback.mockRestore(); vi.useRealTimers(); }
 });
 it.each(['resolves','rejects'])('preserves a recovered session when an expired callback later %s',async completion=> {
  vi.useFakeTimers();
  let finish!:(user:User)=>void; let fail!:(error:Error)=>void;
  const callback=vi.spyOn(UserManager.prototype,'signinRedirectCallback').mockImplementationOnce(()=>new Promise((resolve,reject)=>{finish=resolve; fail=reject;}));
  const user=(token:string)=>new User({access_token:token,token_type:'Bearer',expires_at:Math.floor(Date.now()/1000)+300,profile:{sub:'subject',iss:config.authority,aud:'public',iat:1,exp:9999999999}});
  try {
   const auth=TestBed.inject(AuthService); const result=vi.fn();
   const pending=auth.callback(config.redirectUri+'?code=old&state=old').catch(error=>result(error.message));
   await vi.advanceTimersByTimeAsync(15000); await pending;
   expect(result).toHaveBeenCalledWith('Sign-in could not be verified. Please sign in again.');
   callback.mockResolvedValue(user('recovered'));
   await auth.callback(config.redirectUri+'?code=fresh&state=fresh');
   if (completion === 'resolves') finish(user('expired')); else fail(new Error('late failure'));
   await vi.advanceTimersByTimeAsync(0);
   expect(auth.token).toBe('recovered');
   TestBed.tick(); auth.user.set(null); TestBed.tick(); await vi.advanceTimersByTimeAsync(0);
   expect(vi.getTimerCount()).toBe(0);
  } finally { callback.mockRestore(); vi.useRealTimers(); }
 });
 it.each(['success','failure'])('does not rewrite later navigation when a delayed callback ends with %s',async outcome=> {
  let finish!:(user:User)=>void; let fail!:(error:Error)=>void;
  const callback=vi.spyOn(UserManager.prototype,'signinRedirectCallback').mockImplementation(()=>new Promise((resolve,reject)=>{finish=resolve;fail=reject;}));
  try {
   history.replaceState({},document.title,'/callback?code=secret&state=transaction');
   const auth=TestBed.inject(AuthService);
   const pending=auth.callback(config.redirectUri+'?code=secret&state=transaction').catch(()=>{});
   history.replaceState({},document.title,'/');
   if(outcome === 'failure') fail(new Error('provider rejected'));
   else finish(new User({access_token:'access',token_type:'Bearer',expires_at:Math.floor(Date.now()/1000)+300,profile:{sub:'subject',iss:config.authority,aud:'public',iat:1,exp:9999999999}}));
   await pending;
   expect(location.pathname).toBe('/');
   expect(location.search).toBe('');
  } finally { callback.mockRestore(); history.replaceState({},document.title,'/'); }
 });
 it('sends a fresh nonce with each hosted-UI authorization', async () => {
  const redirect = vi.spyOn(UserManager.prototype,'signinRedirect').mockResolvedValue();
  const auth = TestBed.inject(AuthService);
  await auth.login(); await auth.login();
  const first = redirect.mock.calls[0][0]!;
  expect(first.nonce).toMatch(/^[0-9a-f-]{36}$/); expect(redirect.mock.calls[1][0]!.nonce).not.toBe(first.nonce);
  redirect.mockRestore();
 });
 it('uses code + PKCE with session-only transactions and memory-only users', async () => {
  const settings = authSettings(config);
  expect(settings.response_type).toBe('code'); expect(settings.disablePKCE).toBe(false);
  expect(settings.automaticSilentRenew).toBe(false);
  expect(settings.requestTimeoutInSeconds).toBe(15);
  await settings.stateStore!.set('transaction','temporary');
  expect(sessionStorage.getItem('oidc.transaction')).toBe('temporary');
  await settings.userStore!.set('user','secret');
  expect(sessionStorage.getItem('oidc.user')).toBeNull(); expect(localStorage.length).toBe(0);
 });
});
