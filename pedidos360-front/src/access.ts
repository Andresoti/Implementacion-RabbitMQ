import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { throwError } from 'rxjs';
import { AuthService } from './auth';
import { CONFIG } from './config';
export const authGuard: CanActivateFn = () => inject(AuthService).token ? true : inject(Router).parseUrl('/');
export const tokenInterceptor: HttpInterceptorFn = (request, next) => {
 const config = inject(CONFIG);
 let url: URL;
 try { url = new URL(request.url, location.origin); } catch { return next(request); }
 const protectedEndpoints = [config.apiBaseUrl + '/api/pedidos', config.workerApiBaseUrl + '/api/results'];
 if (!protectedEndpoints.includes(url.origin + url.pathname) || url.username || url.password) return next(request);
 const token = inject(AuthService).token;
 if (!token) return throwError(() => new HttpErrorResponse({status:401,statusText:'Sign in required'}));
 return next(request.clone({setHeaders:{Authorization:'Bearer '+token},redirect:'error'}));
};
