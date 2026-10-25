import { beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter, UrlTree } from '@angular/router';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { firstValueFrom } from 'rxjs';
import { User } from 'oidc-client-ts';
import { AuthService } from './auth';
import { CONFIG, parseConfig } from './config';
import { authGuard, tokenInterceptor } from './access';
const config = parseConfig({authority:'https://issuer.example',clientId:'public',logoutDomain:'https://login.example',redirectUri:'http://localhost:4200/callback',logoutRedirectUri:'http://localhost:4200/',apiBaseUrl:'http://localhost:8080/gateway',workerApiBaseUrl:'http://localhost:8081',scopes:'openid email pedidos/read pedidos/write'});
export function validUser(scope = 'pedidos/read pedidos/write') { return new User({access_token:'secret-access',token_type:'Bearer',scope,expires_at:Math.floor(Date.now()/1000)+300,profile:{sub:'subject',iss:config.authority,aud:'public',iat:1,exp:9999999999}}); }
describe('protected navigation and token transport', () => {
 beforeEach(() => { TestBed.configureTestingModule({providers:[{provide:CONFIG,useValue:config},provideRouter([]),provideHttpClient(withInterceptors([tokenInterceptor])),provideHttpClientTesting()]}); });
 it('returns the login URL without a live session and permits a live session', () => {
  const check = () => TestBed.runInInjectionContext(() => authGuard({} as never, {} as never));
  expect(TestBed.inject(Router).serializeUrl(check() as UrlTree)).toBe('/');
  const auth = TestBed.inject(AuthService); auth.user.set(validUser()); expect(check()).toBe(true);
  auth.user()!.expires_at=1; expect(check()).not.toBe(true);
 });
 it.each(['http://localhost:8080/gateway/api/pedidos','http://localhost:8081/api/results'])('attaches a live bearer only to configured contract endpoint %s', async url => {
  TestBed.inject(AuthService).user.set(validUser()); const pending=firstValueFrom(TestBed.inject(HttpClient).get(url));
  const request=TestBed.inject(HttpTestingController).expectOne(url); expect(request.request.headers.get('Authorization')).toBe('Bearer secret-access'); expect(request.request.redirect).toBe('error'); request.flush([]); await pending;
 });
 it.each(['https://attacker.example/api/pedidos','http://localhost:8080.evil.example/gateway/api/pedidos','http://localhost:8080/gateway/api/pedidos-extra','http://localhost:8080/other/api/pedidos','http://localhost:8081/arbitrary'])('never sends the token to %s', async url => {
  TestBed.inject(AuthService).user.set(validUser()); const pending=firstValueFrom(TestBed.inject(HttpClient).get(url));
  const request=TestBed.inject(HttpTestingController).expectOne(url); expect(request.request.headers.has('Authorization')).toBe(false); request.flush([]); await pending;
 });
 it('blocks protected network calls when the session expires', async () => {
  const auth=TestBed.inject(AuthService); auth.user.set(validUser()); auth.user()!.expires_at=1;
  await expect(firstValueFrom(TestBed.inject(HttpClient).get(config.apiBaseUrl+'/api/pedidos'))).rejects.toMatchObject({status:401});
  TestBed.inject(HttpTestingController).expectNone(config.apiBaseUrl+'/api/pedidos');
 });
});
