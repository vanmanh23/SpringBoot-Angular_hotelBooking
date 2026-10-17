import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/auth.guards';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Stay Hotel',
    loadComponent: () => import('./features/home/home.page').then((m) => m.HomePage),
  },
  {
    path: 'hotels',
    title: 'Hotels · Stay Hotel',
    loadComponent: () => import('./features/hotels/hotel-search.page').then((m) => m.HotelSearchPage),
  },
  {
    path: 'hotels/:id',
    title: 'Hotel · Stay Hotel',
    loadComponent: () => import('./features/hotels/hotel-detail.page').then((m) => m.HotelDetailPage),
  },
  {
    path: 'login',
    title: 'Sign in · Stay Hotel',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/login.page').then((m) => m.LoginPage),
  },
  {
    path: 'register',
    title: 'Create account · Stay Hotel',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/register.page').then((m) => m.RegisterPage),
  },
  {
    path: 'profile',
    title: 'My profile · Stay Hotel',
    canActivate: [authGuard],
    loadComponent: () => import('./features/profile/profile.page').then((m) => m.ProfilePage),
  },
  {
    path: 'forbidden',
    title: 'Access denied',
    loadComponent: () => import('./features/forbidden/forbidden.page').then((m) => m.ForbiddenPage),
  },
  {
    path: '**',
    title: 'Page not found',
    loadComponent: () => import('./features/not-found/not-found.page').then((m) => m.NotFoundPage),
  },
];
