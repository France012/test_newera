import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ClaimsListPage } from './ClaimsListPage';
import { listClaims } from '../api/claims';
import { ApiError } from '../api/client';
import type { ClaimSummary } from '../types/claims';

// The page is tested against the API module boundary: no fetch, no server, no timing races.
vi.mock('../api/claims', () => ({
  DEFAULT_PAGE_SIZE: 20,
  listClaims: vi.fn(),
}));

const listClaimsMock = vi.mocked(listClaims);

function claim(overrides: Partial<ClaimSummary> = {}): ClaimSummary {
  return {
    id: '3f2a1c9d-0000-4000-8000-000000000001',
    claimNumber: 'PR-2024-0001',
    policyNumber: 'POL-77120',
    customerId: '9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d',
    claimType: 'AUTO',
    status: 'UNDER_REVIEW',
    description: 'Danno laterale',
    incidentDate: '2024-09-12',
    openedAt: '2024-09-13T09:15:00Z',
    updatedAt: '2024-09-14T10:00:00Z',
    documentIds: [],
    ...overrides,
  };
}

function renderPage(initialEntry = '/claims') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <ClaimsListPage />
    </MemoryRouter>,
  );
}

describe('ClaimsListPage', () => {
  it('renders the claims returned by the API', async () => {
    listClaimsMock.mockResolvedValue([claim(), claim({ id: 'x-2', claimNumber: 'PR-2024-0002' })]);

    renderPage();

    expect(await screen.findByText('PR-2024-0001')).toBeInTheDocument();
    expect(screen.getByText('PR-2024-0002')).toBeInTheDocument();
    expect(screen.queryByText('Nessuna pratica trovata')).not.toBeInTheDocument();
  });

  it('requests the status taken from the URL', async () => {
    listClaimsMock.mockResolvedValue([]);

    renderPage('/claims?status=APPROVED');

    await waitFor(() => {
      expect(listClaimsMock).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'APPROVED', page: 0 }),
      );
    });
  });

  it('shows the empty state when the filter matches nothing', async () => {
    listClaimsMock.mockResolvedValue([]);

    renderPage('/claims?status=CLOSED');

    expect(await screen.findByText('Nessuna pratica trovata')).toBeInTheDocument();
  });

  it('surfaces a failing request instead of an empty table', async () => {
    listClaimsMock.mockRejectedValue(
      new ApiError({ kind: 'http', status: 502, message: 'Servizio non disponibile' }),
    );

    renderPage();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Servizio non disponibile');
  });

  it('refetches with the selected status when the filter changes', async () => {
    listClaimsMock.mockResolvedValue([claim()]);
    const user = userEvent.setup();

    renderPage();
    await screen.findByText('PR-2024-0001');

    await user.selectOptions(screen.getByLabelText('Stato'), 'APPROVED');

    await waitFor(() => {
      expect(listClaimsMock).toHaveBeenLastCalledWith(
        expect.objectContaining({ status: 'APPROVED' }),
      );
    });
  });
});
