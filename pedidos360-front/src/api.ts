import { inject, Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom, Observable, timeout, TimeoutError } from 'rxjs';
import { CONFIG } from './config';
import { AuthService } from './auth';
export interface Order { id:number; cliente:string; producto:string; cantidad:number; estado:string; }
export interface OrderInput { cliente:string; producto:string; cantidad:number | string; }
export interface ProcessingResult { eventId:string; orderId:number; domain:'NOTIFICATION'|'TICKET'|'DOCUMENT'; result:string; processedAt:string; }
export class ApiError extends Error {
 constructor(message:string, readonly status=0) { super(message); }
}
function validText(value:unknown, max:number):value is string {
 return typeof value === 'string' && !!value.trim() && value.trim().length <= max && !/[\u0000\uD800-\uDFFF]/u.test(value);
}
export function validateOrder(input: OrderInput): {cliente:string;producto:string;cantidad:number} {
 const cliente = input.cliente.trim(), producto = input.producto.trim(), cantidad = Number(input.cantidad);
 if (!validText(cliente,100)) throw new Error('Customer must contain 1–100 nonblank characters.');
 if (!validText(producto,200)) throw new Error('Product must contain 1–200 nonblank characters.');
 if (!Number.isInteger(cantidad) || cantidad < 1 || cantidad > 10000) throw new Error('Quantity must be a whole number from 1 to 10000.');
 return {cliente, producto, cantidad};
}
function isOrder(value: unknown): value is Order {
 if (!value || typeof value !== 'object') return false;
 const order = value as Order;
 return Number.isSafeInteger(order.id) && order.id > 0 && validText(order.cliente,100) && validText(order.producto,200) && Number.isInteger(order.cantidad) && order.cantidad >= 1 && order.cantidad <= 10000 && typeof order.estado === 'string';
}
function isResult(value: unknown): value is ProcessingResult {
 if (!value || typeof value !== 'object') return false;
 const result = value as ProcessingResult;
 return typeof result.eventId === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(result.eventId) && Number.isInteger(result.orderId) && result.orderId > 0 && ['NOTIFICATION','TICKET','DOCUMENT'].includes(result.domain) && typeof result.result === 'string' && typeof result.processedAt === 'string' && /^\d{4}-\d{2}-\d{2}T/.test(result.processedAt) && Number.isFinite(Date.parse(result.processedAt));
}
@Injectable({providedIn:'root'})
export class OrdersApi {
 private readonly http = inject(HttpClient);
 private readonly config = inject(CONFIG);
 private readonly auth = inject(AuthService);
 private require(scope:string):void {
  if (!this.auth.token) throw new ApiError('Your session has expired. Please sign in again.',401);
  if (!this.auth.hasScope(scope)) throw new ApiError('Missing permission: '+scope+'.',403);
 }
 private async response<T>(request:Observable<T>):Promise<T> {
  const session=this.auth.user();
  const check=() => { if (session !== this.auth.user()) throw new ApiError('Session changed. Please refresh after signing in.',409); };
  let data:T;
  try { data=await firstValueFrom(request.pipe(timeout(15000))); }
  catch(error) {
   check();
   if (error instanceof TimeoutError) throw new ApiError('The request timed out. Check the API connection.');
   if (!(error instanceof HttpErrorResponse)) throw error;
   if (error.status === 401) { this.auth.user.set(null); throw new ApiError('Your session has expired. Please sign in again.',401); }
   if (error.status === 403) throw new ApiError('You do not have permission for this action.',403);
   if (error.status === 0) throw new ApiError('Network error. Check the API connection and try again.');
   if (error.status >= 200 && error.status < 300) throw new ApiError('Invalid API response. Expected JSON.');
   throw new ApiError(error.status >= 500 ? 'The server could not complete the request. Try again.' : 'The request was rejected. Check your input and try again.',error.status);
  }
  check();
  if (!this.auth.token) { this.auth.user.set(null); throw new ApiError('Your session has expired. Please sign in again.',401); }
  return data;
 }
 async list():Promise<Order[]> {
  this.require('pedidos/read');
  const data=await this.response(this.http.get<unknown>(this.config.apiBaseUrl+'/api/pedidos'));
  if (!Array.isArray(data) || !data.every(isOrder)) throw new ApiError('Invalid orders response.');
  return data;
 }
 async create(input:OrderInput):Promise<Order> {
  this.require('pedidos/write');
  const payload=validateOrder(input);
  try {
   const response=await this.response(this.http.post<unknown>(this.config.apiBaseUrl+'/api/pedidos',payload,{observe:'response'}));
   if (response.status !== 201) throw new ApiError('Invalid create response: expected HTTP 201.');
   if (!isOrder(response.body)) throw new ApiError('Invalid order response.');
   if (response.body.estado !== 'CREATED') throw new ApiError('Invalid order response: expected CREATED state.');
   return response.body;
  } catch(error) {
   if (error instanceof ApiError && (error.status === 0 || error.status === 408 || error.status >= 500)) {
    const message=/^Invalid/.test(error.message)?error.message:'Could not confirm order creation (network error, server error or request timed out).';
    throw new ApiError(message+' The order may have been created. Refresh orders before submitting again.',error.status);
   }
   throw error;
  }
 }
 async results():Promise<ProcessingResult[]> {
  this.require('pedidos/read');
  const data=await this.response(this.http.get<unknown>(this.config.workerApiBaseUrl+'/api/results'));
  if (!Array.isArray(data) || !data.every(isResult)) throw new ApiError('Invalid processing results response.');
  return data;
 }
}
