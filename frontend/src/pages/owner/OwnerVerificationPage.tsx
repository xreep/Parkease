import { useMemo, useState, type ReactNode } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'
import { toast } from 'sonner'
import { FormError } from '../../components/AuthCard'
import { Button } from '../../components/ui/Button'
import { Select } from '../../components/ui/Select'
import { Spinner } from '../../components/ui/Spinner'
import { StatusBadge } from '../../components/ui/StatusBadge'
import { TextField } from '../../components/ui/TextField'
import { errorMessage } from '../../lib/errors'
import { DOCUMENT_TYPE_LABELS } from '../../lib/format'
import { openInNewTab } from '../../lib/openDocument'
import {
  getDocumentUrl,
  savePayout,
  submitDocument,
  useOwnerProfile,
  type DocumentType,
  type OwnerProfile,
} from '../../lib/owner'

const PROFILE_KEY = ['owner', 'profile']
const EITHER_OR = 'Add a UPI ID, or a bank account with IFSC.'

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-6 dark:border-slate-800 dark:bg-slate-900">
      <h2 className="text-lg font-semibold">{title}</h2>
      <div className="mt-4 space-y-4">{children}</div>
    </section>
  )
}

const documentSchema = z.object({
  documentType: z.enum(Object.keys(DOCUMENT_TYPE_LABELS) as [DocumentType, ...DocumentType[]]),
  file: z.custom<FileList>((v) => v instanceof FileList && v.length > 0, 'Choose a file to upload'),
})
type DocumentValues = z.infer<typeof documentSchema>

function DocumentForm({ profile }: { profile: OwnerProfile }) {
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const { register, handleSubmit, reset, formState: { errors, isSubmitting } } = useForm<DocumentValues>({
    resolver: zodResolver(documentSchema),
    defaultValues: { documentType: profile.documentType ?? 'AADHAAR' },
  })

  async function onSubmit(values: DocumentValues) {
    setFormError(null)
    setProgress(0)
    try {
      const updated = await submitDocument(values.documentType, values.file[0], setProgress)
      queryClient.setQueryData(PROFILE_KEY, updated)
      reset({ documentType: values.documentType })
      toast.success('Document submitted for review')
    } catch (error) {
      setFormError(errorMessage(error))
    } finally {
      setProgress(null)
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
      <FormError message={formError} />
      <Select label="Document type" error={errors.documentType?.message} {...register('documentType')}>
        {Object.entries(DOCUMENT_TYPE_LABELS).map(([value, label]) => (
          <option key={value} value={value}>{label}</option>
        ))}
      </Select>
      <TextField
        label="Document file"
        type="file"
        accept="image/jpeg,image/png,image/webp,application/pdf"
        hint="JPG, PNG, WebP or PDF, up to 5 MB"
        error={errors.file?.message}
        {...register('file')}
      />
      {progress !== null && <p className="text-sm text-slate-600 dark:text-slate-400" aria-live="polite">Uploading… {progress}%</p>}
      <Button type="submit" loading={isSubmitting}>Submit for verification</Button>
    </form>
  )
}

function ViewDocumentButton() {
  const [loading, setLoading] = useState(false)
  async function open() {
    setLoading(true)
    try {
      await openInNewTab(getDocumentUrl)
    } catch (error) {
      toast.error(errorMessage(error))
    } finally {
      setLoading(false)
    }
  }
  return (
    <Button type="button" variant="secondary" loading={loading} onClick={() => void open()}>View document</Button>
  )
}

function IdentitySection({ profile }: { profile: OwnerProfile }) {
  const verified = profile.verificationStatus === 'VERIFIED'
  return (
    <Section title="Identity verification">
      <StatusBadge kind="verification" status={profile.verificationStatus} />
      {profile.rejectionReason && (
        <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-950/50 dark:text-red-300">
          Reason: <strong className="font-semibold">{profile.rejectionReason}</strong>
        </p>
      )}
      {verified ? (
        <p className="text-sm text-slate-600 dark:text-slate-400">Your identity is verified.</p>
      ) : (
        <DocumentForm profile={profile} />
      )}
      {profile.hasDocument && <ViewDocumentButton />}
    </Section>
  )
}

function payoutSchemaFor(hasSavedAccount: boolean) {
  return z
    .object({
      upiId: z.string().trim().regex(/^([a-zA-Z0-9._-]{2,256}@[a-zA-Z]{2,64})?$/, 'Enter a valid UPI ID'),
      bankAccount: z.string().trim().regex(/^(\d{9,18})?$/, 'Enter 9 to 18 digits'),
      ifsc: z.string().trim().regex(/^([A-Za-z]{4}0[A-Za-z0-9]{6})?$/, 'Enter a valid IFSC code'),
      accountName: z.string().trim().min(1, 'Enter the account holder name').max(100, 'Use at most 100 characters'),
      removeBank: z.boolean(),
    })
    .refine(
      (v) => {
        const bankHalf = v.bankAccount !== '' || (hasSavedAccount && !v.removeBank)
        return v.upiId !== '' || (bankHalf && v.ifsc !== '')
      },
      { message: EITHER_OR, path: ['form'] },
    )
    .refine((v) => !(v.removeBank && v.bankAccount !== ''), {
      message: 'Clear this field, or untick "Remove saved bank account"',
      path: ['bankAccount'],
    })
}
type PayoutValues = z.infer<ReturnType<typeof payoutSchemaFor>>

function PayoutForm({ profile }: { profile: OwnerProfile }) {
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const last4 = profile.payoutBankAccountLast4
  const schema = useMemo(() => payoutSchemaFor(last4 !== null), [last4])
  const { register, handleSubmit, reset, formState: { errors, isSubmitting } } = useForm<PayoutValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      upiId: profile.payoutUpi ?? '',
      bankAccount: '',
      ifsc: profile.payoutIfsc ?? '',
      accountName: profile.payoutAccountName ?? '',
      removeBank: false,
    },
  })
  const eitherOrError = (errors as Record<string, { message?: string } | undefined>).form?.message

  async function onSubmit({ removeBank, bankAccount, ...values }: PayoutValues) {
    setFormError(null)
    // bankAccount is tri-state on the server: omitted keeps the saved account, "" clears it.
    const bank = bankAccount !== '' ? { bankAccount } : removeBank ? { bankAccount: '' } : {}
    try {
      const updated = await savePayout({ ...values, ...bank, ifsc: values.ifsc.toUpperCase() })
      queryClient.setQueryData(PROFILE_KEY, updated)
      reset({
        upiId: updated.payoutUpi ?? '',
        bankAccount: '',
        ifsc: updated.payoutIfsc ?? '',
        accountName: updated.payoutAccountName ?? '',
        removeBank: false,
      })
      toast.success('Payout details saved')
    } catch (error) {
      setFormError(errorMessage(error))
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
      {eitherOrError || formError ? (
        <FormError message={eitherOrError ?? formError} />
      ) : (
        <p className="text-sm text-slate-600 dark:text-slate-400">{EITHER_OR}</p>
      )}
      <TextField label="UPI ID" autoComplete="off" error={errors.upiId?.message} {...register('upiId')} />
      <TextField
        label="Bank account number"
        inputMode="numeric"
        autoComplete="off"
        placeholder={last4 ? `•••• ${last4}` : undefined}
        hint={last4 ? `Leave blank to keep •••• ${last4}` : undefined}
        error={errors.bankAccount?.message}
        {...register('bankAccount')}
      />
      {last4 && (
        <label className="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
          <input type="checkbox" className="h-4 w-4 rounded border-slate-300" {...register('removeBank')} />
          Remove saved bank account
        </label>
      )}
      <TextField label="IFSC code" autoComplete="off" error={errors.ifsc?.message} {...register('ifsc')} />
      <TextField label="Account holder name" autoComplete="off" error={errors.accountName?.message} {...register('accountName')} />
      <Button type="submit" loading={isSubmitting}>Save payout details</Button>
    </form>
  )
}

export function OwnerVerificationPage() {
  const { data: profile, error, isPending } = useOwnerProfile()
  if (isPending) {
    return (
      <div className="flex justify-center py-12">
        <Spinner className="h-8 w-8 text-brand-600" />
      </div>
    )
  }
  if (error) return <FormError message={errorMessage(error)} />
  return (
    <div className="space-y-6">
      <IdentitySection profile={profile} />
      <Section title="Payout details">
        <PayoutForm profile={profile} />
      </Section>
    </div>
  )
}
