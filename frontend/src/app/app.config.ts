import { ApplicationConfig, isDevMode, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { Gateway } from './core/gateway';
import { MockGateway } from './core/mock-gateway';
import { WsGateway } from './core/ws-gateway';

/** `?mock` forces the in-browser dataset, `?live` the real backend; dev builds default to mock. */
function createGateway(): Gateway {
  const p = new URLSearchParams(location.search);
  return p.has('mock') || (isDevMode() && !p.has('live')) ? new MockGateway() : new WsGateway();
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    { provide: Gateway, useFactory: createGateway },
  ],
};
