import { convertToParamMap } from '@angular/router';
import { toSearchParams } from './hotel-api';
import { parseSearchQuery, toQueryParams } from './search-query';

describe('search query mapping', () => {
  it('parses valid URL params into a typed query', () => {
    const q = parseSearchQuery(
      convertToParamMap({
        city: ' Đà Nẵng ',
        checkIn: '2030-01-10',
        checkOut: '2030-01-12',
        guests: '3',
        rooms: '2',
        stars: '5,4',
        amenities: 'WIFI,POOL',
        sort: 'price_asc',
        page: '2',
      }),
    );
    expect(q).toMatchObject({
      city: 'Đà Nẵng',
      checkIn: '2030-01-10',
      checkOut: '2030-01-12',
      guests: 3,
      rooms: 2,
      stars: [5, 4],
      amenities: ['WIFI', 'POOL'],
      sort: 'price_asc',
      page: 2,
    });
  });

  it('drops invalid or tampered values instead of forwarding them', () => {
    const q = parseSearchQuery(
      convertToParamMap({
        guests: '-1',
        rooms: 'abc',
        stars: '9,x,3',
        amenities: 'wifi,DROP TABLE,POOL',
        sort: 'cheapest',
        checkIn: '2030-01-10',
        page: '1.5',
      }),
    );
    expect(q.guests).toBeUndefined();
    expect(q.rooms).toBeUndefined();
    expect(q.stars).toEqual([3]);
    expect(q.amenities).toEqual(['POOL']);
    expect(q.sort).toBeUndefined();
    expect(q.checkIn).toBeUndefined(); // only one of the two dates
    expect(q.page).toBeUndefined();
  });

  it('omits empty values when building URL and HTTP params', () => {
    const query = { city: 'Hue', stars: [], amenities: ['WIFI', 'SPA'], page: undefined, guests: 2 };
    expect(toQueryParams(query)).toEqual({ city: 'Hue', amenities: 'WIFI,SPA', guests: 2 });
    expect(toSearchParams(query).toString()).toBe('city=Hue&amenities=WIFI,SPA&guests=2');
  });
});
