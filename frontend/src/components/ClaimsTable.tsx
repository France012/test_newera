import { Link } from 'react-router-dom';
import { ClaimStatusBadge } from './ClaimStatusBadge';
import type { ClaimSummary } from '../types/claims';
import { formatDate } from '../utils/format';

export interface ClaimsTableProps {
  claims: ClaimSummary[];
}

/**
 * Dense table of claims.
 *
 * The claim number is the navigation target: the whole row is not clickable, because a
 * row-level click handler would swallow text selection and break keyboard navigation.
 */
export function ClaimsTable({ claims }: ClaimsTableProps) {
  return (
    <div className="table-wrapper">
      <table className="table">
        <caption className="visually-hidden">Elenco delle pratiche</caption>
        <thead>
          <tr>
            <th scope="col">Pratica</th>
            <th scope="col">Polizza</th>
            <th scope="col">Tipo</th>
            <th scope="col">Stato</th>
            <th scope="col">Data sinistro</th>
            <th scope="col">Aperta il</th>
          </tr>
        </thead>
        <tbody>
          {claims.map((claim) => (
            <tr key={claim.id}>
              <th scope="row" className="table__cell-id">
                <Link className="link" to={`/claims/${claim.id}`}>
                  {claim.claimNumber}
                </Link>
              </th>
              <td>{claim.policyNumber}</td>
              <td>{claim.claimType}</td>
              <td>
                <ClaimStatusBadge status={claim.status} size="sm" />
              </td>
              <td className="table__cell-mono">{formatDate(claim.incidentDate)}</td>
              <td className="table__cell-mono">{formatDate(claim.openedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
