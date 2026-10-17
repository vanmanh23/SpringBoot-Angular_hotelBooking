import { FormControl, FormGroup } from '@angular/forms';
import { addDays, isoDate } from '../../shared/forms/date-utils';
import { stayDatesValidator } from './search-form';

describe('stayDatesValidator', () => {
  const today = isoDate(new Date());
  const check = (checkIn: string, checkOut: string) =>
    stayDatesValidator(new FormGroup({ checkIn: new FormControl(checkIn), checkOut: new FormControl(checkOut) }));

  it('accepts no dates or a valid stay', () => {
    expect(check('', '')).toBeNull();
    expect(check(today, addDays(today, 2))).toBeNull();
    expect(check(today, addDays(today, 30))).toBeNull();
  });

  it('rejects incomplete, past, reversed and too long stays', () => {
    expect(check(today, '')).toEqual({ datesIncomplete: true });
    expect(check(addDays(today, -1), today)).toEqual({ checkInPast: true });
    expect(check(addDays(today, 3), addDays(today, 3))).toEqual({ checkOutBeforeCheckIn: true });
    expect(check(today, addDays(today, 31))).toEqual({ stayTooLong: true });
  });
});
