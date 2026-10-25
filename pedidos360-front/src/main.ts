import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { App, routes } from './app';
import { CONFIG } from './config';
import { tokenInterceptor } from './access';
import { startApplication } from './startup';
void startApplication(config => bootstrapApplication(App, {providers:[
 {provide:CONFIG,useValue:config},
 provideHttpClient(withInterceptors([tokenInterceptor])),
 provideRouter(routes)
]}));
