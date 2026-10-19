import { Routes } from '@angular/router';
import { authGuard, guestGuard, roleGuard } from './core/auth/auth.guards';

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
    path: 'booking/new',
    title: 'Complete your booking · Stay Hotel',
    canActivate: [authGuard],
    loadComponent: () => import('./features/booking/checkout.page').then((m) => m.CheckoutPage),
  },
  {
    path: 'bookings/:id',
    title: 'Booking · Stay Hotel',
    canActivate: [authGuard],
    loadComponent: () => import('./features/booking/booking-detail.page').then((m) => m.BookingDetailPage),
  },
  {
    path: 'my-bookings',
    title: 'My bookings · Stay Hotel',
    canActivate: [authGuard],
    loadComponent: () => import('./features/booking/my-bookings.page').then((m) => m.MyBookingsPage),
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
    path: 'notifications',
    title: 'Notifications · Stay Hotel',
    canActivate: [authGuard],
    loadComponent: () => import('./features/notifications/notifications.page').then((m) => m.NotificationsPage),
  },
  {
    path: 'manage',
    title: 'Manage hotels · Stay Hotel',
    canActivate: [roleGuard('HOTEL_MANAGER', 'ADMIN')],
    loadComponent: () => import('./features/manage/manage-hotels.page').then((m) => m.ManageHotelsPage),
  },
  {
    path: 'manage/hotels/:id',
    title: 'Manage hotel · Stay Hotel',
    canActivate: [roleGuard('HOTEL_MANAGER', 'ADMIN')],
    loadComponent: () => import('./features/manage/manage-hotel.page').then((m) => m.ManageHotelPage),
  },
  {
    path: 'admin',
    title: 'Administration · Stay Hotel',
    canActivate: [roleGuard('ADMIN')],
    loadComponent: () => import('./features/admin/admin.page').then((m) => m.AdminPage),
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
